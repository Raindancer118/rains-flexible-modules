package de.raindancer.modules.invsnap.store;

import de.raindancer.modules.invsnap.model.Snapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("one file per player, holding their whole snapshot history")
class SnapshotStoreTest {

    @TempDir
    Path folder;

    @Test
    @DisplayName("nothing on disk is an empty list, not an error")
    void loadingNothingIsEmpty() {
        assertThat(new SnapshotStore(folder).load(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("a snapshot survives a round trip exactly — every slot, in order, empty slots included")
    void roundTrip() {
        SnapshotStore store = new SnapshotStore(folder);
        UUID player = UUID.randomUUID();
        Instant takenAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Snapshot snapshot = new Snapshot(player, "Someone", takenAt,
                List.of("sword-line", Snapshot.EMPTY_SLOT, "pickaxe-line"),
                List.of(Snapshot.EMPTY_SLOT, Snapshot.EMPTY_SLOT, "boots-line", "helmet-line"),
                "shield-line");

        assertThat(store.saveAll(player, List.of(snapshot))).isTrue();
        List<Snapshot> loaded = store.load(player);

        assertThat(loaded).hasSize(1);
        Snapshot back = loaded.getFirst();
        assertThat(back.playerId()).isEqualTo(player);
        assertThat(back.playerName()).isEqualTo("Someone");
        assertThat(back.takenAt()).isEqualTo(takenAt);
        assertThat(back.mainInventory()).containsExactly("sword-line", Snapshot.EMPTY_SLOT, "pickaxe-line");
        assertThat(back.armor()).containsExactly(
                Snapshot.EMPTY_SLOT, Snapshot.EMPTY_SLOT, "boots-line", "helmet-line");
        assertThat(back.offHand()).isEqualTo("shield-line");
    }

    @Test
    @DisplayName("saving replaces the whole history, not appends to it")
    void saveAllReplacesEverything() {
        SnapshotStore store = new SnapshotStore(folder);
        UUID player = UUID.randomUUID();
        Snapshot first = new Snapshot(player, "Someone", Instant.now(), List.of(), List.of(), null);
        Snapshot second = new Snapshot(player, "Someone",
                Instant.now().plusSeconds(60), List.of(), List.of(), null);

        store.saveAll(player, List.of(first));
        store.saveAll(player, List.of(second));

        assertThat(store.load(player)).hasSize(1);
    }

    @Test
    @DisplayName("two players never see each other's history")
    void playersAreKeptSeparate() {
        SnapshotStore store = new SnapshotStore(folder);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        store.saveAll(first, List.of(
                new Snapshot(first, "One", Instant.now(), List.of(), List.of(), null)));

        assertThat(store.load(second)).isEmpty();
        assertThat(store.load(first)).hasSize(1);
    }

    @Test
    @DisplayName("nothing on disk means nobody is known yet")
    void knownPlayerIdsStartsEmpty() {
        assertThat(new SnapshotStore(folder).knownPlayerIds()).isEmpty();
    }

    @Test
    @DisplayName("every player saved at least once is in the roster, and nobody else is")
    void knownPlayerIdsIsExactlyWhoWasSaved() {
        SnapshotStore store = new SnapshotStore(folder);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        store.saveAll(first, List.of(
                new Snapshot(first, "One", Instant.now(), List.of(), List.of(), null)));
        store.saveAll(second, List.of(
                new Snapshot(second, "Two", Instant.now(), List.of(), List.of(), null)));

        assertThat(store.knownPlayerIds()).containsExactlyInAnyOrder(first, second);
    }

    @Test
    @DisplayName("a stray file that is not a player id is skipped rather than failing the whole roster")
    void knownPlayerIdsSkipsStrayFiles() throws java.io.IOException {
        SnapshotStore store = new SnapshotStore(folder);
        UUID player = UUID.randomUUID();
        store.saveAll(player, List.of(
                new Snapshot(player, "Someone", Instant.now(), List.of(), List.of(), null)));
        java.nio.file.Files.writeString(folder.resolve("snapshots").resolve("not-a-uuid.yml"), "junk: true");

        assertThat(store.knownPlayerIds()).containsExactly(player);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("a prune waits for a snapshot being written for the same player, so neither undoes the other")
    void pruneWaitsForAWrite() throws Exception {
        SnapshotStore store = new SnapshotStore(folder);
        UUID player = UUID.randomUUID();
        java.time.Instant old = java.time.Instant.now().minusSeconds(86_400 * 30).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        java.time.Instant fresh = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        store.saveAll(player, java.util.List.of(new Snapshot(player, "Someone", old, java.util.List.of(), java.util.List.of(), null)));

        java.util.concurrent.CountDownLatch writing = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch pruneStarted = new java.util.concurrent.CountDownLatch(1);
        Thread writer = new Thread(() -> store.change(player, all -> {
            writing.countDown();
            try {
                pruneStarted.await();
                Thread.sleep(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            java.util.List<Snapshot> more = new java.util.ArrayList<>(all);
            more.add(new Snapshot(player, "Someone", fresh, java.util.List.of(), java.util.List.of(), null));
            return more;
        }));
        writer.start();
        writing.await();
        Thread pruner = new Thread(() -> {
            pruneStarted.countDown();
            store.pruneOlderThan(java.time.Instant.now().minusSeconds(86_400));
        });
        pruner.start();
        writer.join(5_000);
        pruner.join(5_000);

        assertThat(store.load(player)).extracting(Snapshot::takenAt).containsExactly(fresh);
    }
}
