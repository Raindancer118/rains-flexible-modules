package de.raindancer.modules.sends.store;

import de.raindancer.core.data.nbt.ItemBytes;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.testkit.TestDatabases;
import de.raindancer.modules.sends.model.Send;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The store against the real database, on both dialects, with items standing in for what the game would serialise. */
@Tag("database")
class SqlSendStorageTest {

    @TempDir
    Path folder;

    private final Map<String, ItemStack> world = new HashMap<>();
    private final ItemBytes bytes = new ItemBytes() {
        @Override
        public int dataVersion() {
            return 1;
        }

        @Override
        public byte[] toBytes(ItemStack item) {
            return item.toString().getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public Optional<ItemStack> fromBytes(byte[] data) {
            return Optional.ofNullable(world.get(new String(data, StandardCharsets.UTF_8)));
        }
    };

    private Database database;
    private SqlSendStorage storage;

    @BeforeEach
    void open() {
        database = TestDatabases.open(folder.resolve("sends.db"), SendsDatabase.SCHEMA);
        storage = new SqlSendStorage(database, bytes);
    }

    @AfterEach
    void close() {
        database.close();
    }

    private ItemStack item(String name, int amount) {
        ItemStack item = mock(ItemStack.class);
        when(item.toString()).thenReturn(name);
        when(item.getAmount()).thenReturn(amount);
        world.put(name, item);
        return item;
    }

    private Send send(ItemStack... items) {
        return Send.create(UUID.randomUUID(), "Alice", UUID.randomUUID(), "Bob", List.of(items), 1_000, 600_000);
    }

    @Test
    @DisplayName("a send comes back as it was written, items in order")
    void roundTrip() {
        Send written = send(item("diamond", 3), item("stone", 64));
        assertThat(storage.save(written)).isTrue();

        List<Send> loaded = storage.loadOpen().orElseThrow();
        assertThat(loaded).hasSize(1);
        Send read = loaded.getFirst();
        assertThat(read.id()).isEqualTo(written.id());
        assertThat(read.senderName()).isEqualTo("Alice");
        assertThat(read.recipientName()).isEqualTo("Bob");
        assertThat(read.expiresAt()).isEqualTo(601_000);
        assertThat(read.state()).isEqualTo(Send.State.PENDING);
        assertThat(read.items()).extracting(Object::toString).containsExactly("diamond", "stone");
    }

    @Test
    @DisplayName("saving a change moves the state and leaves the items alone")
    void stateChange() {
        Send written = send(item("diamond", 3));
        storage.save(written);
        assertThat(storage.change(written, written.withState(Send.State.RETURNING, 5_000)))
                .isEqualTo(SendStorage.Written.DONE);

        List<Send> loaded = storage.loadOpen().orElseThrow();
        assertThat(loaded).hasSize(1);
        assertThat(loaded.getFirst().state()).isEqualTo(Send.State.RETURNING);
        assertThat(loaded.getFirst().settledAt()).isEqualTo(5_000);
        assertThat(loaded.getFirst().items()).hasSize(1);
    }

    @Test
    @DisplayName("a send that has been delivered or returned is not loaded again")
    void settledStaysSettled() {
        Send delivered = send(item("a", 1));
        Send returned = send(item("b", 1));
        Send waiting = send(item("c", 1));
        storage.save(delivered);
        storage.save(returned);
        storage.save(waiting);
        storage.change(delivered, delivered.withState(Send.State.DELIVERED, 2));
        storage.change(returned, returned.withState(Send.State.RETURNED, 2));

        assertThat(storage.loadOpen().orElseThrow()).extracting(Send::id).containsExactly(waiting.id());
    }

    @Test
    @DisplayName("a send whose items can no longer be read is not loaded, rather than loaded empty")
    void unreadableItemsAreNotLoadedAsNothing() {
        Send written = send(item("gone", 1));
        storage.save(written);
        world.clear();
        assertThat(storage.loadOpen().orElseThrow()).isEmpty();
    }

    @Test
    @DisplayName("what was saved survives closing and opening the file")
    void survivesRestart() {
        Send written = send(item("diamond", 3));
        storage.save(written);
        database.close();
        open();
        assertThat(storage.loadOpen().orElseThrow()).extracting(Send::id).containsExactly(written.id());
    }

    @Test
    @DisplayName("a send is read back by its id, and one that is settled is not open")
    void loadsOne() {
        Send written = send(item("diamond", 3));
        storage.save(written);
        assertThat(storage.load(written.id())).get().satisfies(found -> assertThat(found).get()
                .extracting(Send::id).isEqualTo(written.id()));
        storage.change(written, written.withState(Send.State.DELIVERED, 9));
        assertThat(storage.load(written.id())).get().satisfies(found -> assertThat(found).isEmpty());
        assertThat(storage.load(UUID.randomUUID())).get().satisfies(found -> assertThat(found).isEmpty());
    }

    @Test
    @DisplayName("saving the same send twice writes it once")
    void savingTwiceIsOnce() {
        Send written = send(item("diamond", 3), item("stone", 1));
        assertThat(storage.save(written)).isTrue();
        assertThat(storage.save(written)).isTrue();
        assertThat(storage.loadOpen().orElseThrow()).hasSize(1);
        assertThat(storage.loadOpen().orElseThrow().getFirst().items()).hasSize(2);
    }

    @Test
    @DisplayName("a move is made once: the same move again, or a different one from the old state, is lost")
    void aMoveIsMadeOnce() {
        Send written = send(item("diamond", 3));
        storage.save(written);
        Send delivered = written.withState(Send.State.DELIVERED, 5);

        assertThat(storage.change(written, delivered)).isEqualTo(SendStorage.Written.DONE);
        assertThat(storage.change(written, delivered)).isEqualTo(SendStorage.Written.LOST);
        assertThat(storage.change(written, written.withState(Send.State.RETURNING, 6)))
                .isEqualTo(SendStorage.Written.LOST);
        assertThat(storage.change(delivered, written)).as("putting it back is a move too")
                .isEqualTo(SendStorage.Written.DONE);
        assertThat(storage.loadOpen().orElseThrow()).extracting(Send::state).containsExactly(Send.State.PENDING);
    }

    @Test
    @DisplayName("two servers racing to end the same sends: each send is ended by exactly one of them, one way")
    void racingServersEndEachSendOnce() throws Exception {
        Database otherDatabase = TestDatabases.open(folder.resolve("sends.db"), SendsDatabase.SCHEMA);
        try {
            SqlSendStorage first = storage;
            SqlSendStorage second = new SqlSendStorage(otherDatabase, bytes);
            int count = 40;
            List<Send> sends = new java.util.ArrayList<>();
            for (int i = 0; i < count; i++) {
                Send made = send(item("item" + i, 1));
                first.save(made);
                sends.add(made);
            }
            java.util.concurrent.atomic.AtomicInteger accepted = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger cancelled = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            java.util.concurrent.Future<?> recipientsServer = pool.submit(() -> {
                go.await();
                for (Send each : sends) {
                    if (first.change(each, each.withState(Send.State.DELIVERED, 10)) == SendStorage.Written.DONE) {
                        accepted.incrementAndGet();
                    }
                }
                return null;
            });
            java.util.concurrent.Future<?> sendersServer = pool.submit(() -> {
                go.await();
                for (int i = sends.size() - 1; i >= 0; i--) {
                    Send each = sends.get(i);
                    if (second.change(each, each.withState(Send.State.RETURNING, 11)) == SendStorage.Written.DONE) {
                        cancelled.incrementAndGet();
                    }
                }
                return null;
            });
            go.countDown();
            recipientsServer.get(60, java.util.concurrent.TimeUnit.SECONDS);
            sendersServer.get(60, java.util.concurrent.TimeUnit.SECONDS);
            pool.shutdown();

            assertThat(accepted.get() + cancelled.get()).as("every send was ended, none twice").isEqualTo(count);
            long stillReturning = first.loadOpen().orElseThrow().stream()
                    .filter(send -> send.state() == Send.State.RETURNING).count();
            assertThat(stillReturning).isEqualTo(cancelled.get());
            assertThat(first.loadOpen().orElseThrow()).allMatch(send -> send.state() == Send.State.RETURNING);
        } finally {
            otherDatabase.close();
        }
    }
}
