package de.raindancer.modules.hungergames;

import de.raindancer.modules.hungergames.store.RuntimeStore;
import de.raindancer.modules.hungergames.store.RuntimeStore.DeathmatchState;
import de.raindancer.modules.hungergames.store.RuntimeStore.SupplyDropState;
import de.raindancer.modules.hungergames.store.RuntimeStore.TokenState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link RuntimeStore}: the op snapshot and the schedule marks that stop a restart double-firing anything. */
class RuntimeStoreTest {

    @Test
    @DisplayName("the OP snapshot survives a save and a load")
    void opSnapshotRoundTrip(@TempDir Path dir) {
        RuntimeStore store = new RuntimeStore(dir.resolve("runtime.yml"));
        UUID admin = UUID.randomUUID();

        store.saveOpSnapshot(Set.of(admin));

        assertThat(store.loadOpSnapshot()).containsExactly(admin);
        assertThat(store.problems()).isEmpty();
    }

    @Test
    @DisplayName("the supply-drop marks survive a save and a load")
    void supplyDropRoundTrip(@TempDir Path dir) {
        RuntimeStore store = new RuntimeStore(dir.resolve("runtime.yml"));
        SupplyDropState state = new SupplyDropState(Set.of(0, 2), java.util.List.of("100.0,64.0,200.0"));

        store.saveSupplyDropState(state);

        assertThat(store.loadSupplyDropState()).isEqualTo(state);
    }

    @Test
    @DisplayName("the sponsor-token marks survive a save and a load")
    void tokenStateRoundTrip(@TempDir Path dir) {
        RuntimeStore store = new RuntimeStore(dir.resolve("runtime.yml"));
        UUID player = UUID.randomUUID();

        store.saveTokenState(Map.of(player, new TokenState(3, 90)));

        assertThat(store.loadTokenState()).containsEntry(player, new TokenState(3, 90));
    }

    @Test
    @DisplayName("the three sections coexist: saving one never erases the others")
    void sectionsDoNotClobberEachOther(@TempDir Path dir) {
        RuntimeStore store = new RuntimeStore(dir.resolve("runtime.yml"));
        UUID admin = UUID.randomUUID();
        UUID player = UUID.randomUUID();

        store.saveOpSnapshot(Set.of(admin));
        store.saveSupplyDropState(new SupplyDropState(Set.of(1), java.util.List.of()));
        store.saveTokenState(Map.of(player, new TokenState(1, 10)));

        assertThat(store.loadOpSnapshot()).containsExactly(admin);
        assertThat(store.loadSupplyDropState().triggeredIndices()).containsExactly(1);
        assertThat(store.loadTokenState()).containsEntry(player, new TokenState(1, 10));
    }

    @Test
    @DisplayName("the deathmatch phase and when its warning started survive a save and a load")
    void deathmatchStateRoundTrip(@TempDir Path dir) {
        // This is the one a restart between the warning and the deathmatch itself depends on: without it
        // the round comes back with the border never closing and nobody warned a second time, and the only
        // way out is an admin forcing the round to end in front of everybody watching.
        RuntimeStore store = new RuntimeStore(dir.resolve("runtime.yml"));
        DeathmatchState state = new DeathmatchState(DeathmatchState.Phase.WARNING, 123_456_789L);

        store.saveDeathmatchState(state);

        assertThat(store.loadDeathmatchState()).isEqualTo(state);
    }

    @Test
    @DisplayName("a missing deathmatch section reads as OFF, not as a parse failure")
    void deathmatchStateDefaultsToOff(@TempDir Path dir) {
        RuntimeStore store = new RuntimeStore(dir.resolve("runtime.yml"));
        store.saveOpSnapshot(Set.of(UUID.randomUUID())); // some other section exists, deathmatch does not

        assertThat(store.loadDeathmatchState()).isEqualTo(DeathmatchState.off());
    }

    @Test
    @DisplayName("saving the deathmatch state never erases the other sections, and vice versa")
    void deathmatchStateCoexistsWithTheOtherSections(@TempDir Path dir) {
        RuntimeStore store = new RuntimeStore(dir.resolve("runtime.yml"));
        UUID admin = UUID.randomUUID();

        store.saveOpSnapshot(Set.of(admin));
        store.saveDeathmatchState(new DeathmatchState(DeathmatchState.Phase.ACTIVE, 0L));

        assertThat(store.loadOpSnapshot()).containsExactly(admin);
        assertThat(store.loadDeathmatchState())
                .isEqualTo(new DeathmatchState(DeathmatchState.Phase.ACTIVE, 0L));
    }

    @Test
    @DisplayName("no file yet reads as empty everywhere, never an exception")
    void missingFile(@TempDir Path dir) {
        RuntimeStore store = new RuntimeStore(dir.resolve("runtime.yml"));

        assertThat(store.loadOpSnapshot()).isEmpty();
        assertThat(store.loadSupplyDropState()).isEqualTo(SupplyDropState.empty());
        assertThat(store.loadTokenState()).isEmpty();
        assertThat(store.loadDeathmatchState()).isEqualTo(DeathmatchState.off());
        assertThat(store.problems()).isEmpty();
    }

    @Test
    @DisplayName("a corrupt file is quarantined rather than trusted or overwritten in place")
    void corruptFileIsQuarantined(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("runtime.yml");
        Files.writeString(file, "deopped-admins: [not valid: [[[");
        RuntimeStore store = new RuntimeStore(file);

        assertThat(store.loadOpSnapshot()).isEmpty();
        assertThat(store.problems()).isNotEmpty();
        assertThat(Files.exists(file)).isFalse();

        // the next save starts a clean file rather than being blocked forever
        UUID admin = UUID.randomUUID();
        store.saveOpSnapshot(Set.of(admin));
        assertThat(store.loadOpSnapshot()).containsExactly(admin);
    }

    @Test
    @DisplayName("clear removes the whole file")
    void clearRemovesTheFile(@TempDir Path dir) {
        RuntimeStore store = new RuntimeStore(dir.resolve("runtime.yml"));
        store.saveOpSnapshot(Set.of(UUID.randomUUID()));

        store.clear();

        assertThat(store.loadOpSnapshot()).isEmpty();
    }
}
