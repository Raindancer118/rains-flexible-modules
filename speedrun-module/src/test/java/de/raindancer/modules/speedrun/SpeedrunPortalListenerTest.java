package de.raindancer.modules.speedrun;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Keeping a run's portal travel inside the run's own three worlds — the bug this exists for is
 * walking out of a nether portal and standing in the server's overworld, outside the race entirely.
 */
class SpeedrunPortalListenerTest {

    private SpeedrunLobby lobby;
    private SpeedrunPortalListener listener;

    @BeforeEach
    void setUp() {
        lobby = mock(SpeedrunLobby.class);
        when(lobby.config()).thenReturn(new SpeedrunSettings("speedrun", "minecraft:end/kill_dragon",
                SpeedrunDeathPolicy.OFF, false, 100, 0, 100, 0, false, 0, 0, 0, 0, 0));
        listener = new SpeedrunPortalListener(lobby);
    }

    private static World worldNamed(String name, World.Environment environment) {
        World world = mock(World.class);
        when(world.getName()).thenReturn(name);
        when(world.getEnvironment()).thenReturn(environment);
        return world;
    }

    private static PlayerPortalEvent travel(World from, World to) {
        Player player = mock(Player.class);
        return new PlayerPortalEvent(player, new Location(from, 8, 64, 8), new Location(to, 1, 64, 1),
                PlayerTeleportEvent.TeleportCause.NETHER_PORTAL);
    }

    @Test
    @DisplayName("a portal out of the run's nether comes back into the run's overworld, not the server's")
    void leavingTheNetherStaysInTheRun() {
        World runNether = worldNamed("speedrun_nether", World.Environment.NETHER);
        World serverOverworld = worldNamed("world", World.Environment.NORMAL);
        World runOverworld = worldNamed("speedrun", World.Environment.NORMAL);
        PlayerPortalEvent event = travel(runNether, serverOverworld);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("speedrun")).thenReturn(runOverworld);

            listener.onPortal(event);
        }

        assertThat(event.getTo().getWorld()).isEqualTo(runOverworld);
        // Only the world is wrong: Bukkit already scaled the coordinates for the right kind of
        // dimension, and the run's overworld is the same kind as the server's.
        assertThat(event.getTo().getBlockX()).isEqualTo(1);
    }

    @Test
    @DisplayName("a portal out of the run's overworld goes into the run's own nether")
    void enteringTheNetherStaysInTheRun() {
        World runOverworld = worldNamed("speedrun", World.Environment.NORMAL);
        World serverNether = worldNamed("world_nether", World.Environment.NETHER);
        World runNether = worldNamed("speedrun_nether", World.Environment.NETHER);
        PlayerPortalEvent event = travel(runOverworld, serverNether);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("speedrun_nether")).thenReturn(runNether);

            listener.onPortal(event);
        }

        assertThat(event.getTo().getWorld()).isEqualTo(runNether);
    }

    @Test
    @DisplayName("travel that already lands in the right world is left exactly as it is")
    void correctTravelIsUntouched() {
        World runOverworld = worldNamed("speedrun", World.Environment.NORMAL);
        World runNether = worldNamed("speedrun_nether", World.Environment.NETHER);
        PlayerPortalEvent event = travel(runOverworld, runNether);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("speedrun_nether")).thenReturn(runNether);

            listener.onPortal(event);
        }

        assertThat(event.getTo().getWorld()).isEqualTo(runNether);
        assertThat(event.isCancelled()).isFalse();
    }

    @Test
    @DisplayName("a portal somewhere else on the server is none of this module's business")
    void travelOutsideTheRunIsIgnored() {
        World serverOverworld = worldNamed("world", World.Environment.NORMAL);
        World serverNether = worldNamed("world_nether", World.Environment.NETHER);
        PlayerPortalEvent event = travel(serverOverworld, serverNether);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            listener.onPortal(event);
        }

        assertThat(event.getTo().getWorld()).isEqualTo(serverNether);
    }

    @Test
    @DisplayName("with the run's dimension not loaded, the travel is left alone rather than broken")
    void missingDimensionLeavesTravelAlone() {
        World runOverworld = worldNamed("speedrun", World.Environment.NORMAL);
        World serverNether = worldNamed("world_nether", World.Environment.NETHER);
        PlayerPortalEvent event = travel(runOverworld, serverNether);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("speedrun_nether")).thenReturn(null);

            listener.onPortal(event);
        }

        assertThat(event.getTo().getWorld()).isEqualTo(serverNether);
    }
}
