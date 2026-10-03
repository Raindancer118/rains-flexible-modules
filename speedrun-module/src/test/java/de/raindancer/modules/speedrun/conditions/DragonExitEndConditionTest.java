package de.raindancer.modules.speedrun.conditions;

import de.raindancer.modules.speedrun.SpeedrunSession;
import de.raindancer.modules.speedrun.SpeedrunState;
import de.raindancer.modules.speedrun.SpeedrunWorlds;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
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
        // Arming revokes the goal so it can be granted again — see GoalAdvancement; there is no
        // server here to ask for it, and these tests are about what happens once the run is under way.
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getAdvancement(KEY)).thenReturn(null);
            session.addEndCondition(condition);
            session.start();
        }
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

    private static EntityDeathEvent deathOf(LivingEntity entity) {
        EntityDeathEvent event = mock(EntityDeathEvent.class);
        when(event.getEntity()).thenReturn(entity);
        return event;
    }

    private static World worldIn(World.Environment environment) {
        World world = mock(World.class);
        when(world.getEnvironment()).thenReturn(environment);
        return world;
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

    /**
     * The real failure this guards: advancements are per player and survive a world reset, so a racer
     * who has ever killed the dragon before is never granted {@code end/kill_dragon} again — no event,
     * no flag, and the exit portal then ended nothing at all. The dragon dying is the fact the run
     * actually turns on, so that is what arms the portal.
     */
    @Test
    void theDragonDyingArmsThePortalEvenWithoutTheAdvancement() {
        condition.onDragonDeath(deathOf(mock(EnderDragon.class)));
        condition.onExitPortal(new PlayerPortalEvent(playerWithId(ALICE), in(World.Environment.THE_END),
                in(World.Environment.NORMAL), PlayerTeleportEvent.TeleportCause.END_PORTAL));

        assertThat(session.state()).isEqualTo(SpeedrunState.FINISHED);
    }

    @Test
    void someOtherMobDyingDoesNotArmThePortal() {
        condition.onDragonDeath(deathOf(mock(Zombie.class)));
        condition.onExitPortal(new PlayerPortalEvent(playerWithId(ALICE), in(World.Environment.THE_END),
                in(World.Environment.NORMAL), PlayerTeleportEvent.TeleportCause.END_PORTAL));

        assertThat(session.state()).isEqualTo(SpeedrunState.RUNNING);
    }

    private static org.bukkit.event.player.PlayerRespawnEvent respawnAfter(Player player,
            org.bukkit.event.player.PlayerRespawnEvent.RespawnReason reason) {
        org.bukkit.event.player.PlayerRespawnEvent event = mock(org.bukkit.event.player.PlayerRespawnEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getRespawnReason()).thenReturn(reason);
        return event;
    }

    /**
     * The exit portal is not an ordinary portal trip — the server runs the credits and then respawns
     * the player with reason END_PORTAL. That respawn only ever happens to the one who walked through,
     * so it is the second, authoritative way in.
     */
    @Test
    void finishesOnTheRespawnAfterTheEndCredits() {
        condition.onDragonDeath(deathOf(mock(EnderDragon.class)));

        condition.onEndCredits(respawnAfter(playerWithId(ALICE),
                org.bukkit.event.player.PlayerRespawnEvent.RespawnReason.END_PORTAL));

        assertThat(session.state()).isEqualTo(SpeedrunState.FINISHED);
    }

    @Test
    void anOrdinaryRespawnIsNotTheExitPortal() {
        condition.onDragonDeath(deathOf(mock(EnderDragon.class)));

        condition.onEndCredits(respawnAfter(playerWithId(ALICE),
                org.bukkit.event.player.PlayerRespawnEvent.RespawnReason.DEATH));
        condition.onEndCredits(respawnAfter(playerWithId(ALICE),
                org.bukkit.event.player.PlayerRespawnEvent.RespawnReason.PLUGIN));

        assertThat(session.state()).isEqualTo(SpeedrunState.RUNNING);
    }

    @Test
    void theCreditsBeforeTheKillEndNothing() {
        condition.onEndCredits(respawnAfter(playerWithId(ALICE),
                org.bukkit.event.player.PlayerRespawnEvent.RespawnReason.END_PORTAL));

        assertThat(session.state()).isEqualTo(SpeedrunState.RUNNING);
    }

    @Test
    void aNonParticipantsCreditsEndNothing() {
        condition.onDragonDeath(deathOf(mock(EnderDragon.class)));

        condition.onEndCredits(respawnAfter(playerWithId(BOB),
                org.bukkit.event.player.PlayerRespawnEvent.RespawnReason.END_PORTAL));

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

    /**
     * Manhunt's rule: only a Runner walking out ends the hunt. A Hunter taking the exit portal after
     * the kill — portal event and credits alike — is somebody going home, not a win for anybody.
     */
    @Test
    void onlyAParticipantWhoCountsEndsItThroughThePortal() {
        SpeedrunSession hunt = armed(Set.of(ALICE, BOB), ALICE::equals);
        DragonExitEndCondition runnersOnly = lastArmed;
        runnersOnly.onDragonDeath(deathOf(mock(EnderDragon.class)));

        runnersOnly.onExitPortal(new PlayerPortalEvent(playerWithId(BOB), in(World.Environment.THE_END),
                in(World.Environment.NORMAL), PlayerTeleportEvent.TeleportCause.END_PORTAL));
        runnersOnly.onEndCredits(respawnAfter(playerWithId(BOB),
                org.bukkit.event.player.PlayerRespawnEvent.RespawnReason.END_PORTAL));
        assertThat(hunt.state()).isEqualTo(SpeedrunState.RUNNING);

        runnersOnly.onEndCredits(respawnAfter(playerWithId(ALICE),
                org.bukkit.event.player.PlayerRespawnEvent.RespawnReason.END_PORTAL));
        assertThat(hunt.state()).isEqualTo(SpeedrunState.FINISHED);
    }

    /**
     * Leaving the End any other way — dying, a command, a plugin, the join handler sending a
     * reconnecting player back to the lobby — used to count through a "changed world out of the End"
     * fallback. There is no such fallback any more: the class has no handler for it at all.
     */
    @Test
    void changingWorldIsNotAWayIn() {
        assertThat(java.util.Arrays.stream(DragonExitEndCondition.class.getMethods())
                .flatMap(method -> java.util.Arrays.stream(method.getParameterTypes())))
                .doesNotContain((Class) PlayerChangedWorldEvent.class);
    }

    private DragonExitEndCondition lastArmed;

    private SpeedrunSession armed(Set<UUID> participants, java.util.function.Predicate<UUID> counts) {
        Server server = mock(Server.class);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        Plugin plugin = mock(Plugin.class);
        when(plugin.getServer()).thenReturn(server);
        SpeedrunSession fresh = new SpeedrunSession(participants);
        lastArmed = new DragonExitEndCondition(plugin, KEY, counts);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getAdvancement(KEY)).thenReturn(null);
            fresh.addEndCondition(lastArmed);
            fresh.start();
        }
        return fresh;
    }

    /** The run's three worlds are speedrun / speedrun_nether / speedrun_the_end. */
    @org.junit.jupiter.api.Nested
    @org.junit.jupiter.api.DisplayName("only the run's own End counts")
    class ScopedToTheRun {

        private SpeedrunSession run;
        private DragonExitEndCondition scoped;

        @BeforeEach
        void arm() {
            Server server = mock(Server.class);
            when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
            Plugin plugin = mock(Plugin.class);
            when(plugin.getServer()).thenReturn(server);
            run = new SpeedrunSession(Set.of(ALICE));
            scoped = new DragonExitEndCondition(plugin, KEY, id -> true, SpeedrunWorlds.around("speedrun"));
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getAdvancement(KEY)).thenReturn(null);
                run.addEndCondition(scoped);
                run.start();
            }
        }

        private World named(String name, World.Environment environment) {
            World world = mock(World.class);
            when(world.getName()).thenReturn(name);
            when(world.getEnvironment()).thenReturn(environment);
            return world;
        }

        private EnderDragon dragonIn(World world) {
            EnderDragon dragon = mock(EnderDragon.class);
            when(dragon.getWorld()).thenReturn(world);
            return dragon;
        }

        private PlayerPortalEvent exitPortalOf(World end) {
            Location from = mock(Location.class);
            when(from.getWorld()).thenReturn(end);
            return new PlayerPortalEvent(playerWithId(ALICE), from,
                    in(World.Environment.NORMAL), PlayerTeleportEvent.TeleportCause.END_PORTAL);
        }

        @Test
        @org.junit.jupiter.api.DisplayName("a dragon killed in the server's own End does not arm the run's exit portal")
        void aDragonElsewhereDoesNotCount() {
            World runEnd = named("speedrun_the_end", World.Environment.THE_END);
            scoped.onDragonDeath(deathOf(dragonIn(named("world_the_end", World.Environment.THE_END))));

            scoped.onExitPortal(exitPortalOf(runEnd));

            assertThat(run.state()).isEqualTo(SpeedrunState.RUNNING);
        }

        @Test
        @org.junit.jupiter.api.DisplayName("the dragon of the run's End arms it, and its exit portal ends the run")
        void theRunsDragonCounts() {
            World runEnd = named("speedrun_the_end", World.Environment.THE_END);
            scoped.onDragonDeath(deathOf(dragonIn(runEnd)));

            scoped.onExitPortal(exitPortalOf(runEnd));

            assertThat(run.state()).isEqualTo(SpeedrunState.FINISHED);
        }

        @Test
        @org.junit.jupiter.api.DisplayName("the server's own exit portal ends nothing, even once the run's dragon is dead")
        void anotherExitPortalDoesNotCount() {
            scoped.onDragonDeath(deathOf(dragonIn(named("speedrun_the_end", World.Environment.THE_END))));

            scoped.onExitPortal(exitPortalOf(named("world_the_end", World.Environment.THE_END)));

            assertThat(run.state()).isEqualTo(SpeedrunState.RUNNING);
        }

        @Test
        @org.junit.jupiter.api.DisplayName("the end credits after the server's own End end nothing")
        void anotherEndsCreditsDoNotCount() {
            scoped.onDragonDeath(deathOf(dragonIn(named("speedrun_the_end", World.Environment.THE_END))));
            Player alice = playerWithId(ALICE);
            World serversEnd = named("world_the_end", World.Environment.THE_END);
            when(alice.getWorld()).thenReturn(serversEnd);

            scoped.onEndCredits(respawnAfter(alice,
                    org.bukkit.event.player.PlayerRespawnEvent.RespawnReason.END_PORTAL));

            assertThat(run.state()).isEqualTo(SpeedrunState.RUNNING);
        }

        @Test
        @org.junit.jupiter.api.DisplayName("the end credits after the run's End end the run")
        void theRunsEndCreditsCount() {
            scoped.onDragonDeath(deathOf(dragonIn(named("speedrun_the_end", World.Environment.THE_END))));
            Player alice = playerWithId(ALICE);
            World runEnd = named("speedrun_the_end", World.Environment.THE_END);
            when(alice.getWorld()).thenReturn(runEnd);

            scoped.onEndCredits(respawnAfter(alice,
                    org.bukkit.event.player.PlayerRespawnEvent.RespawnReason.END_PORTAL));

            assertThat(run.state()).isEqualTo(SpeedrunState.FINISHED);
        }

        /** A run resumed after a restart, in an End whose dragon died before it: still winnable. */
        @Test
        @org.junit.jupiter.api.DisplayName("a dragon already dead before the run was resumed still arms the exit portal")
        void anAlreadyDeadDragonArms() {
            scoped.dragonAlreadyKilled();

            scoped.onExitPortal(exitPortalOf(named("speedrun_the_end", World.Environment.THE_END)));

            assertThat(run.state()).isEqualTo(SpeedrunState.FINISHED);
        }
    }
}
