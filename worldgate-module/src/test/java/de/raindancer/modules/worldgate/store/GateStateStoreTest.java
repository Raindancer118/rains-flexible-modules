package de.raindancer.modules.worldgate.store;

import de.raindancer.modules.worldgate.model.GateState;
import de.raindancer.modules.worldgate.model.GateStates;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("whether the Nether and the End are open, on disk")
class GateStateStoreTest {

    @TempDir
    Path folder;

    @Test
    @DisplayName("nothing on disk is both dimensions open, not an error")
    void missingFileIsAllOpen() {
        assertThat(new GateStateStore(folder).load()).isEqualTo(GateStates.ALL_OPEN);
    }

    @Test
    @DisplayName("a write survives a round trip exactly")
    void roundTrip() {
        GateStateStore store = new GateStateStore(folder);
        GateStates written = new GateStates(GateState.CLOSED, GateState.DRAINED);

        assertThat(store.save(written)).isTrue();

        assertThat(store.load()).isEqualTo(written);
    }

    @Test
    @DisplayName("a file that will not parse is read as both open, not a crash")
    void unreadableFileIsAllOpen() throws Exception {
        Files.writeString(folder.resolve("worldgate.yml"), "nether: [this is not valid yaml");

        assertThat(new GateStateStore(folder).load()).isEqualTo(GateStates.ALL_OPEN);
    }

    @Test
    @DisplayName("a value that is not a state falls back to open for that dimension only")
    void unknownValueFallsBackToOpenForThatDimensionOnly() throws Exception {
        Files.writeString(folder.resolve("worldgate.yml"), """
                nether: not-a-real-state
                end: CLOSED
                """);

        GateStates loaded = new GateStateStore(folder).load();

        assertThat(loaded.nether()).isEqualTo(GateState.OPEN);
        assertThat(loaded.end()).isEqualTo(GateState.CLOSED);
    }

    @Test
    @DisplayName("the state names round-trip case-insensitively")
    void caseInsensitive() throws Exception {
        Files.writeString(folder.resolve("worldgate.yml"), """
                nether: drained
                end: closed
                """);

        GateStates loaded = new GateStateStore(folder).load();

        assertThat(loaded.nether()).isEqualTo(GateState.DRAINED);
        assertThat(loaded.end()).isEqualTo(GateState.CLOSED);
    }
}
