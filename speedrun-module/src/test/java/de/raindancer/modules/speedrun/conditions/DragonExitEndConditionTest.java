package de.raindancer.modules.speedrun.conditions;

import de.raindancer.modules.speedrun.SpeedrunSession;
import de.raindancer.modules.speedrun.SpeedrunState;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ending a dragon-kill run the way it is actually judged: the advancement alone only arms the
 * condition, and it is stepping into the exit portal afterwards that ends the run — see the class
 * javadoc on {@link DragonExitEndCondition} for why a single advancement event is not enough.
 */
class DragonExitEndConditionTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());
    private static final NamespacedKey KEY = NamespacedKey.minecraft("end/kill_dragon");

    private SpeedrunSession session;
    private DragonExitEndCondition condition;

    @BeforeEach
    void setUp() {
        Server server = mock(Server.class);
        PluginManager manager = mock(PluginManager.class);
        when(server.getPluginManager()).thenReturn(manager);
        Plugin plugin = mock(Plugin.class);
        when(plugin.getServer()).thenReturn(server);

        session = new SpeedrunSession(Set.of(ALICE));
        condition = new DragonExitEndCondition(plugin, KEY);
        session.addEndCondition(condition);
        session.start();
    }

    private static Player playerWithId(UUID id) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        return player;
    }

    private static Advancement advancementWithKey(NamespacedKey key) {
        Advancement advancement = mock(Advancement.class);
        when(advancement.getKey()).thenReturn(key);
        return advancement;
    }

    private static Location in(World.Environment environment) {
        World world = mock(World.class);
        when(world.getEnvironment()).thenReturn(environment);
        Location location = mock(Location.class);
        when(location.getWorld()).thenReturn(world);
        return location;
    }

    @Test
    void killingTheDragonAloneDoesNotFinishTheRun() {
        condition.onAdvancement(
                new PlayerAdvancementDoneEvent(playerWithId(ALICE), advancementWithKey(KEY)));

        assertThat(session.state()).isEqualTo(SpeedrunState.RUNNING);
    }

    @Test
    void reachingTheExitPortalWithoutTheKillDoesNotFinishTheRun() {
        condition.onExitPortal(new PlayerPortalEvent(playerWithId(ALICE), in(World.Environment.THE_END),
                in(World.Environment.NORMAL), PlayerTeleportEvent.TeleportCause.END_PORTAL));

        assertThat(session.state()).isEqualTo(SpeedrunState.RUNNING);
    }

    @Test
    void finishesOnceTheDragonIsDeadAndTheExitPortalIsUsed() {
        condition.onAdvancement(
                new PlayerAdvancementDoneEvent(playerWithId(ALICE), advancementWithKey(KEY)));
        condition.onExitPortal(new PlayerPortalEvent(playerWithId(ALICE), in(World.Environment.THE_END),
                in(World.Environment.NORMAL), PlayerTeleportEvent.TeleportCause.END_PORTAL));

        assertThat(session.state()).isEqualTo(SpeedrunState.FINISHED);
        assertThat(session.outcome().orElseThrow().reason()).isEqualTo("advancement:" + KEY);
    }

    @Test
    void ignoresEnteringTheEndEvenAfterTheKill() {
        condition.onAdvancement(
                new PlayerAdvancementDoneEvent(playerWithId(ALICE), advancementWithKey(KEY)));
        // The trip the other way: from the Overworld into the End, same cause, wrong direction.
        condition.onExitPortal(new PlayerPortalEvent(playerWithId(ALICE), in(World.Environment.NORMAL),
                in(World.Environment.THE_END), PlayerTeleportEvent.TeleportCause.END_PORTAL));

        assertThat(session.state()).isEqualTo(SpeedrunState.RUNNING);
    }

    @Test
    void ignoresAPortalUseByANonParticipant() {
        condition.onAdvancement(
                new PlayerAdvancementDoneEvent(playerWithId(ALICE), advancementWithKey(KEY)));
        condition.onExitPortal(new PlayerPortalEvent(playerWithId(BOB), in(World.Environment.THE_END),
                in(World.Environment.NORMAL), PlayerTeleportEvent.TeleportCause.END_PORTAL));

        assertThat(session.state()).isEqualTo(SpeedrunState.RUNNING);
    }

    @Test
    void ignoresAnUnrelatedTeleportCause() {
        condition.onAdvancement(
                new PlayerAdvancementDoneEvent(playerWithId(ALICE), advancementWithKey(KEY)));
        condition.onExitPortal(new PlayerPortalEvent(playerWithId(ALICE), in(World.Environment.THE_END),
                in(World.Environment.NORMAL), PlayerTeleportEvent.TeleportCause.NETHER_PORTAL));

        assertThat(session.state()).isEqualTo(SpeedrunState.RUNNING);
    }
}
