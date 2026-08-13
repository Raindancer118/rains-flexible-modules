package de.raindancer.modules.worldgate.service;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.worldgate.WorldGateSettings;
import de.raindancer.modules.worldgate.model.Dimension;
import de.raindancer.modules.worldgate.model.GateState;
import de.raindancer.modules.worldgate.model.GateStates;
import de.raindancer.modules.worldgate.store.GateStateStore;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorldGateServiceTest {

    @TempDir
    Path folder;

    private WorldGateService service(Path dataFolder) {
        return new WorldGateService(new GateStateStore(dataFolder), Log.of("worldgate-test"),
                new Messages(dataFolder.resolve("messages.yml")), WorldGateSettings.DEFAULTS);
    }

    @Nested
    @DisplayName("where an evacuated player goes")
    class EvacuationTarget {

        @Test
        @DisplayName("stays exactly where their own respawn point is, when it is in the overworld")
        void staysAtRespawnInTheOverworld() {
            World overworld = mock(World.class);
            Location respawn = new Location(overworld, 10, 65, -20);

            Location target = WorldGateService.evacuationTarget(respawn, overworld);

            assertThat(target).isEqualTo(respawn);
        }

        @Test
        @DisplayName("falls back to the overworld's own spawn when the respawn point is elsewhere")
        void fallsBackWhenRespawnIsInAnotherWorld() {
            World overworld = mock(World.class);
            World nether = mock(World.class);
            Location overworldSpawn = new Location(overworld, 0, 64, 0);
            when(overworld.getSpawnLocation()).thenReturn(overworldSpawn);
            Location respawnInNether = new Location(nether, 5, 70, 5);

            Location target = WorldGateService.evacuationTarget(respawnInNether, overworld);

            assertThat(target).isEqualTo(overworldSpawn);
        }

        @Test
        @DisplayName("falls back to the overworld's own spawn when there is no respawn point at all")
        void fallsBackWhenRespawnIsNull() {
            World overworld = mock(World.class);
            Location overworldSpawn = new Location(overworld, 0, 64, 0);
            when(overworld.getSpawnLocation()).thenReturn(overworldSpawn);

            Location target = WorldGateService.evacuationTarget(null, overworld);

            assertThat(target).isEqualTo(overworldSpawn);
        }

        @Test
        @DisplayName("falls back when the respawn point's world itself is unknown")
        void fallsBackWhenRespawnHasNoWorld() {
            World overworld = mock(World.class);
            Location overworldSpawn = new Location(overworld, 0, 64, 0);
            when(overworld.getSpawnLocation()).thenReturn(overworldSpawn);
            Location respawnWithNoWorld = Mockito.mock(Location.class);
            when(respawnWithNoWorld.getWorld()).thenReturn(null);

            Location target = WorldGateService.evacuationTarget(respawnWithNoWorld, overworld);

            assertThat(target).isEqualTo(overworldSpawn);
        }
    }

    @Nested
    @DisplayName("locking and unlocking")
    class State {

        @Test
        @DisplayName("a fresh service, before load(), reports both dimensions open")
        void freshServiceIsAllOpen() {
            WorldGateService service = service(folder);

            assertThat(service.states()).isEqualTo(GateStates.ALL_OPEN);
        }

        @Test
        @DisplayName("load() reads whatever was on disk")
        void loadReadsTheStore() {
            new GateStateStore(folder).save(new GateStates(GateState.CLOSED, GateState.OPEN));
            WorldGateService service = service(folder);

            service.load();

            assertThat(service.state(Dimension.NETHER)).isEqualTo(GateState.CLOSED);
            assertThat(service.state(Dimension.END)).isEqualTo(GateState.OPEN);
        }

        @Test
        @DisplayName("set() updates the live state and reaches disk")
        void setPersistsAndUpdatesLiveState() {
            WorldGateService service = service(folder);

            assertThat(service.set(Dimension.END, GateState.DRAINED)).isTrue();

            assertThat(service.state(Dimension.END)).isEqualTo(GateState.DRAINED);
            assertThat(service.state(Dimension.NETHER))
                    .as("setting one dimension must not touch the other")
                    .isEqualTo(GateState.OPEN);
            assertThat(new GateStateStore(folder).load().end()).isEqualTo(GateState.DRAINED);
        }

        @Test
        @DisplayName("locking and evacuating are independent — set() never moves anybody")
        void settingNeverEvacuates() {
            WorldGateService service = service(folder);

            service.set(Dimension.NETHER, GateState.CLOSED);

            assertThat(service.state(Dimension.NETHER)).isEqualTo(GateState.CLOSED);
            // Nothing here has a Server or a Player to move — the point is exactly that set() never
            // asked for one.
        }
    }

    @Nested
    @DisplayName("which world a dimension is")
    class WorldNames {

        @Test
        @DisplayName("comes from the settings, and follows a reload")
        void followsSettingsReload() {
            WorldGateService service = service(folder);

            assertThat(service.worldName(Dimension.NETHER)).isEqualTo("world_nether");

            service.settings(WorldGateSettings.DEFAULTS.withNetherWorld("nether2"));

            assertThat(service.worldName(Dimension.NETHER)).isEqualTo("nether2");
        }
    }
}
