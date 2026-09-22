package de.raindancer.modules.speedrun;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Dying in the run's nether or end and waking up in the server's own overworld — outside the race,
 * in a world no reset ever touches. The mirror image of {@link SpeedrunPortalListenerTest}: portals
 * are one way out of the run's three worlds, a death is the other.
 */
class SpeedrunRespawnListenerTest {

    private World runOverworld;
    private Location wayBackIn;
    private SpeedrunLobby lobby;
    private SpeedrunRespawnListener listener;

    @BeforeEach
    void setUp() {
        runOverworld = worldNamed("speedrun");
        wayBackIn = new Location(runOverworld, 0.5, 64, 0.5);
        lobby = mock(SpeedrunLobby.class);
        when(lobby.config()).thenReturn(new SpeedrunSettings("", "speedrun", "minecraft:end/kill_dragon",
                SpeedrunDeathPolicy.OFF, false, 100, 0, 100, 0, false, 0, 0, 0, 0, 0,
                true, true, true, true, true, 10, true, 1000,
                true, true, true, true, true, true, true, true, true, true,
                false, false));
        when(lobby.wayBackIn()).thenReturn(Optional.of(wayBackIn));
        listener = new SpeedrunRespawnListener(lobby);
    }

    private static World worldNamed(String name) {
        World world = mock(World.class);
        when(world.getName()).thenReturn(name);
        return world;
    }

    private static PlayerRespawnEvent death(World diedIn, World respawnIn) {
        Player player = mock(Player.class);
        when(player.getLastDeathLocation()).thenReturn(diedIn == null ? null : new Location(diedIn, 9, 30, 9));
        when(player.getWorld()).thenReturn(respawnIn);
        return new PlayerRespawnEvent(player, new Location(respawnIn, 100, 64, 100), false);
    }

    @Test
    @DisplayName("a death in the run's nether respawns back in the run, not in the server's overworld")
    void dyingInTheRunsNetherComesBackIntoTheRun() {
        PlayerRespawnEvent event = death(worldNamed("speedrun_nether"), worldNamed("world"));

        listener.onRespawn(event);

        assertThat(event.getRespawnLocation()).isEqualTo(wayBackIn);
    }

    @Test
    @DisplayName("a death in the run's end respawns back in the run too")
    void dyingInTheRunsEndComesBackIntoTheRun() {
        PlayerRespawnEvent event = death(worldNamed("speedrun_the_end"), worldNamed("world"));

        listener.onRespawn(event);

        assertThat(event.getRespawnLocation()).isEqualTo(wayBackIn);
    }

    @Test
    @DisplayName("a respawn point inside the run — a bed, an anchor, the point start set — is left alone")
    void arespawnPointInsideTheRunIsKept() {
        World runNether = worldNamed("speedrun_nether");
        PlayerRespawnEvent event = death(runNether, runNether);
        Location bed = event.getRespawnLocation();

        listener.onRespawn(event);

        assertThat(event.getRespawnLocation()).isEqualTo(bed);
    }

    @Test
    @DisplayName("a death outside the run's worlds is none of this module's business")
    void aDeathElsewhereIsLeftAlone() {
        PlayerRespawnEvent event = death(worldNamed("world_nether"), worldNamed("world"));
        Location untouched = event.getRespawnLocation();

        listener.onRespawn(event);

        assertThat(event.getRespawnLocation()).isEqualTo(untouched);
    }

    @Test
    @DisplayName("without a recorded death location nothing is guessed at, so nothing is changed")
    void anUnknownDeathIsLeftAlone() {
        // The fallback is where the player is during the respawn, which is already the destination:
        // it can decline to redirect, never redirect somebody who did not die in the run.
        PlayerRespawnEvent event = death(null, worldNamed("world"));
        Location untouched = event.getRespawnLocation();

        listener.onRespawn(event);

        assertThat(event.getRespawnLocation()).isEqualTo(untouched);
    }

    @Test
    @DisplayName("with the lobby world gone there is nowhere to send them, so nothing is changed")
    void noLobbyWorldMeansNoRedirect() {
        when(lobby.wayBackIn()).thenReturn(Optional.empty());
        PlayerRespawnEvent event = death(worldNamed("speedrun_nether"), worldNamed("world"));
        Location untouched = event.getRespawnLocation();

        listener.onRespawn(event);

        assertThat(event.getRespawnLocation()).isEqualTo(untouched);
    }
}
