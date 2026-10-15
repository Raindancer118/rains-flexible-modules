package de.raindancer.modules.oneblock.store;

import de.raindancer.modules.oneblock.model.EconomyMode;
import de.raindancer.modules.oneblock.model.Island;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class IslandBookTest {

    /** A store in memory that can be told to fail. */
    static final class MemoryStorage implements IslandStorage {
        final Map<UUID, Island> saved = new HashMap<>();
        final List<UUID> archived = new ArrayList<>();
        volatile boolean failing;

        @Override
        public Optional<List<Island>> loadAll() {
            return failing ? Optional.empty() : Optional.of(List.copyOf(saved.values()));
        }

        @Override
        public synchronized boolean save(Island island) {
            if (failing) {
                return false;
            }
            saved.put(island.id(), island);
            return true;
        }

        @Override
        public synchronized boolean archive(Island island) {
            if (failing) {
                return false;
            }
            saved.remove(island.id());
            archived.add(island.id());
            return true;
        }

        @Override
        public boolean savePosition(UUID player, PlayerSpot spot, long now) {
            return true;
        }

        @Override
        public Optional<PlayerSpot> position(UUID player) {
            return Optional.empty();
        }

        @Override
        public Optional<String> modeOf(UUID player) {
            return Optional.empty();
        }
    }

    private final MemoryStorage storage = new MemoryStorage();
    private final IslandBook book = new IslandBook(storage);

    private static Island island(UUID owner) {
        return Island.create(UUID.randomUUID(), owner, "O", EconomyMode.PURE, 0);
    }

    @Test
    @DisplayName("an island is found by id, by any member and by any of its worlds")
    void lookups() {
        UUID owner = UUID.randomUUID();
        UUID friend = UUID.randomUUID();
        Island island = island(owner);
        book.add(island);
        book.change(island.id(), it -> Optional.of(it.withMember(friend)));

        assertThat(book.get(island.id())).isPresent();
        assertThat(book.ofPlayer(owner)).map(Island::id).contains(island.id());
        assertThat(book.ofPlayer(friend)).map(Island::id).contains(island.id());
        assertThat(book.ofWorld(island.worldName(de.raindancer.modules.oneblock.model.IslandDimension.END)))
                .map(Island::id).contains(island.id());
        assertThat(book.ofWorld("world")).isEmpty();
    }

    @Test
    @DisplayName("nobody is on two islands, whichever way they would get there")
    void oneIslandPerPlayer() {
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        Island mine = island(owner);
        Island theirs = island(other);
        book.add(mine);
        book.add(theirs);

        assertThat(book.add(island(owner)).outcome()).isEqualTo(IslandBook.Outcome.ON_ANOTHER_ISLAND);
        assertThat(book.change(theirs.id(), it -> Optional.of(it.withMember(owner))).outcome())
                .isEqualTo(IslandBook.Outcome.ON_ANOTHER_ISLAND);
        assertThat(book.get(theirs.id()).orElseThrow().isMember(owner)).isFalse();
    }

    @Test
    @DisplayName("somebody who left is free to join another island")
    void leavingFreesThePlayer() {
        UUID owner = UUID.randomUUID();
        UUID friend = UUID.randomUUID();
        Island first = island(owner);
        Island second = island(UUID.randomUUID());
        book.add(first);
        book.add(second);
        book.change(first.id(), it -> Optional.of(it.withMember(friend)));
        book.change(first.id(), it -> Optional.of(it.withoutMember(friend)));

        assertThat(book.change(second.id(), it -> Optional.of(it.withMember(friend))).done()).isTrue();
        assertThat(book.ofPlayer(friend)).map(Island::id).contains(second.id());
    }

    @Test
    @DisplayName("a refused change leaves the island as it was and writes nothing")
    void refusedChange() {
        Island island = island(UUID.randomUUID());
        book.add(island);
        book.flush();
        storage.saved.clear();

        assertThat(book.change(island.id(), it -> Optional.empty()).outcome()).isEqualTo(IslandBook.Outcome.REFUSED);
        assertThat(book.hasUnwritten()).isFalse();
        assertThat(book.change(UUID.randomUUID(), Optional::of).outcome()).isEqualTo(IslandBook.Outcome.NOT_FOUND);
    }

    @Test
    @DisplayName("a flush writes each changed island once, as it is now")
    void flushWritesTheLatest() {
        Island island = island(UUID.randomUUID());
        book.add(island);
        for (int i = 0; i < 5; i++) {
            book.change(island.id(), it -> Optional.of(it.withShardsAdded(1)));
        }

        assertThat(book.flush()).isZero();
        assertThat(storage.saved.get(island.id()).shards()).isEqualTo(5);
        assertThat(book.hasUnwritten()).isFalse();
    }

    @Test
    @DisplayName("what could not be written is tried again at the next flush")
    void failedWritesStay() {
        Island island = island(UUID.randomUUID());
        book.add(island);
        storage.failing = true;

        assertThat(book.flush()).isEqualTo(1);
        assertThat(book.hasUnwritten()).isTrue();
        storage.failing = false;
        assertThat(book.flush()).isZero();
        assertThat(storage.saved).containsKey(island.id());
    }

    @Test
    @DisplayName("archiving takes the island out of play and writes it as archived")
    void archiving() {
        UUID owner = UUID.randomUUID();
        Island island = island(owner);
        book.add(island);
        book.flush();

        assertThat(book.archive(island.id())).isPresent();
        assertThat(book.ofPlayer(owner)).isEmpty();
        assertThat(book.add(island(owner)).done()).as("the owner may start again").isTrue();
        book.flush();
        assertThat(storage.archived).containsExactly(island.id());
    }

    @Test
    @DisplayName("an unreadable store loads nothing and says so")
    void unreadableStore() {
        storage.failing = true;
        assertThat(book.load()).isFalse();
        assertThat(book.size()).isZero();
    }

    @Test
    @DisplayName("eight threads adding shards at once lose none of them")
    void concurrentChangesLoseNothing() throws InterruptedException {
        Island island = island(UUID.randomUUID());
        book.add(island);
        int threads = 8;
        int each = 2_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < each; i++) {
                    book.change(island.id(), it -> Optional.of(it.withShardsAdded(1)));
                    if (i % 97 == 0) {
                        book.flush();
                    }
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        book.flush();

        assertThat(book.get(island.id()).orElseThrow().shards()).isEqualTo((long) threads * each);
        assertThat(storage.saved.get(island.id()).shards()).isEqualTo((long) threads * each);
    }
}
