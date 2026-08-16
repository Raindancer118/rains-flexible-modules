package de.raindancer.modules.invsnap.service;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.invsnap.InvSnapSettings;
import de.raindancer.modules.invsnap.model.Snapshot;
import de.raindancer.modules.invsnap.model.TrackedPlayer;
import de.raindancer.modules.invsnap.rules.RetentionRule;
import de.raindancer.modules.invsnap.store.SnapshotStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@link SnapshotService} can answer without touching a real {@code Player} or {@code
 * ItemStack} — {@code historyOf} and {@code tracked}, both plain reads through a real {@link
 * SnapshotStore}. See {@code AutoSnapshotServiceTest}'s own javadoc for why {@code takeAndStore},
 * {@code liveSnapshotOf} and {@code restore} are not exercised here: those three are the one place
 * in this module that lazily reaches for a running Paper server.
 */
class SnapshotServiceTest {

    private static final LogChannel LOG = Log.of("invsnap-test");

    @TempDir
    Path folder;

    private SnapshotService serviceOver(SnapshotStore store) {
        return new SnapshotService(LOG, store, new RetentionRule(), new InvSnapSettings(300, 24));
    }

    @Test
    @DisplayName("nobody tracked yet is an empty roster, not an error")
    void trackedStartsEmpty() {
        assertThat(serviceOver(new SnapshotStore(folder)).tracked()).isEmpty();
    }

    @Test
    @DisplayName("a player with a saved snapshot is in the roster, with their count and newest moment")
    void trackedReflectsWhatIsStored() {
        SnapshotStore store = new SnapshotStore(folder);
        UUID player = UUID.randomUUID();
        Instant older = Instant.now().minusSeconds(120).truncatedTo(ChronoUnit.MILLIS);
        Instant newer = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        store.saveAll(player, List.of(
                new Snapshot(player, "Someone", older, List.of(), List.of(), null),
                new Snapshot(player, "Someone", newer, List.of(), List.of(), null)));

        List<TrackedPlayer> tracked = serviceOver(store).tracked();

        assertThat(tracked).hasSize(1);
        TrackedPlayer only = tracked.getFirst();
        assertThat(only.id()).isEqualTo(player);
        assertThat(only.name()).isEqualTo("Someone");
        assertThat(only.count()).isEqualTo(2);
        assertThat(only.newest()).isEqualTo(newer);
    }

    @Test
    @DisplayName("the roster is newest activity first")
    void trackedIsOrderedByNewestActivity() {
        SnapshotStore store = new SnapshotStore(folder);
        UUID staleness = UUID.randomUUID();
        UUID freshness = UUID.randomUUID();
        store.saveAll(staleness, List.of(
                new Snapshot(staleness, "Stale", Instant.now().minusSeconds(600),
                        List.of(), List.of(), null)));
        store.saveAll(freshness, List.of(
                new Snapshot(freshness, "Fresh", Instant.now(), List.of(), List.of(), null)));

        List<TrackedPlayer> tracked = serviceOver(store).tracked();

        assertThat(tracked).extracting(TrackedPlayer::id).containsExactly(freshness, staleness);
    }

    @Test
    @DisplayName("historyOf is newest first, regardless of the order saved")
    void historyOfIsNewestFirst() {
        SnapshotStore store = new SnapshotStore(folder);
        UUID player = UUID.randomUUID();
        Instant older = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MILLIS);
        Instant newer = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        store.saveAll(player, List.of(
                new Snapshot(player, "Someone", older, List.of(), List.of(), null),
                new Snapshot(player, "Someone", newer, List.of(), List.of(), null)));

        List<Snapshot> history = serviceOver(store).historyOf(player);

        assertThat(history).extracting(Snapshot::takenAt).containsExactly(newer, older);
    }
}
