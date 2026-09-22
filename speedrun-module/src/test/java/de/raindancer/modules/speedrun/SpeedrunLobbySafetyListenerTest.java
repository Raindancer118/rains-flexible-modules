package de.raindancer.modules.speedrun;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The lobby as a safe room: nobody can be hurt in it, and nothing explodes in it, until a run is
 * actually under way.
 *
 * <h2>Why this is a listener of its own rather than more handlers on {@link SpeedrunLobbyListener}</h2>
 * Lifetime. Everything on that listener is about a player arriving, clicking or leaving; this is about
 * the world refusing harm, it is registered for the life of the module the same way, but it answers a
 * different question and a host can turn each half of it off on its own.
 */
class SpeedrunLobbySafetyListenerTest {

    private SpeedrunLobby lobby;
    private SpeedrunLobbySafetyListener listener;
    private World lobbyWorld;

    private static SpeedrunSettings config(boolean protectPlayers, boolean blockExplosions) {
        return config(protectPlayers, blockExplosions, false, false);
    }

    private static SpeedrunSettings config(boolean protectPlayers, boolean blockExplosions,
                                           boolean mayBreakBlocks, boolean monstersHunt) {
        return new SpeedrunSettings("", "world", "minecraft:end/kill_dragon",
                true, SpeedrunDeathPolicy.OFF, false, 0, 0, 0, 0, false, 0, 0, 0, 0, 0,
                true, protectPlayers, blockExplosions, true, true, 10, true, 1000,
                true, true, true, true, true, true, true, true, true, true,
                mayBreakBlocks, monstersHunt);
    }

    @BeforeEach
    void setUp() {
        lobby = mock(SpeedrunLobby.class);
        listener = new SpeedrunLobbySafetyListener(lobby);
        lobbyWorld = mock(World.class);
        when(lobbyWorld.getName()).thenReturn("world");
        when(lobby.config()).thenReturn(config(true, true));
        when(lobby.state()).thenReturn(SpeedrunLobbyState.READY);
    }

    private Player playerIn(World world) {
        Player player = mock(Player.class);
        when(player.getWorld()).thenReturn(world);
        return player;
    }

    private EntityDamageEvent damageTo(Player player, EntityDamageEvent.DamageCause cause) {
        EntityDamageEvent event = mock(EntityDamageEvent.class);
        when(event.getEntity()).thenReturn(player);
        when(event.getCause()).thenReturn(cause);
        return event;
    }

    @Nested
    @DisplayName("damage before a run")
    class Damage {

        @Test
        @DisplayName("a player hitting another in the lobby is refused")
        void refusesPlayerDamage() {
            EntityDamageEvent event = damageTo(playerIn(lobbyWorld),
                    EntityDamageEvent.DamageCause.ENTITY_ATTACK);

            listener.onDamage(event);

            verify(event).setCancelled(true);
        }

        @Test
        @DisplayName("nothing is refused once the run is actually under way")
        void allowsDamageWhileRunning() {
            when(lobby.state()).thenReturn(SpeedrunLobbyState.RUNNING);
            EntityDamageEvent event = damageTo(playerIn(lobbyWorld),
                    EntityDamageEvent.DamageCause.ENTITY_ATTACK);

            listener.onDamage(event);

            verify(event, never()).setCancelled(true);
        }

        @Test
        @DisplayName("nothing outside the lobby world is this listener's business")
        void ignoresOtherWorlds() {
            World elsewhere = mock(World.class);
            when(elsewhere.getName()).thenReturn("somewhere-else");
            EntityDamageEvent event = damageTo(playerIn(elsewhere),
                    EntityDamageEvent.DamageCause.ENTITY_ATTACK);

            listener.onDamage(event);

            verify(event, never()).setCancelled(true);
        }

        @Test
        @DisplayName("with the protection off, the lobby is as dangerous as anywhere else")
        void allowsDamageWhenTurnedOff() {
            when(lobby.config()).thenReturn(config(false, false));
            EntityDamageEvent event = damageTo(playerIn(lobbyWorld),
                    EntityDamageEvent.DamageCause.ENTITY_ATTACK);

            listener.onDamage(event);

            verify(event, never()).setCancelled(true);
        }

        @Test
        @DisplayName("explosion damage is still refused for a host who only turned explosions off")
        void explosionDamageFollowsTheExplosionSetting() {
            when(lobby.config()).thenReturn(config(false, true));
            EntityDamageEvent event = damageTo(playerIn(lobbyWorld),
                    EntityDamageEvent.DamageCause.ENTITY_EXPLOSION);

            listener.onDamage(event);

            verify(event).setCancelled(true);
        }
    }

    @Nested
    @DisplayName("explosions before a run")
    class Explosions {

        @Test
        @DisplayName("a creeper never even finishes fizzing in the lobby")
        void refusesTheFuse() {
            Creeper creeper = mock(Creeper.class);
            when(creeper.getWorld()).thenReturn(lobbyWorld);
            ExplosionPrimeEvent event = mock(ExplosionPrimeEvent.class);
            when(event.getEntity()).thenReturn(creeper);

            listener.onPrime(event);

            verify(event).setCancelled(true);
        }

        @Test
        @DisplayName("an explosion that does go off takes no blocks with it")
        void refusesTheBlast() {
            EntityExplodeEvent event = mock(EntityExplodeEvent.class);
            Location where = mock(Location.class);
            when(where.getWorld()).thenReturn(lobbyWorld);
            when(event.getLocation()).thenReturn(where);

            listener.onExplode(event);

            verify(event).setCancelled(true);
        }

        @Test
        @DisplayName("a bed or an anchor going off is refused the same way")
        void refusesABlockExplosion() {
            BlockExplodeEvent event = mock(BlockExplodeEvent.class);
            Block block = mock(Block.class);
            when(block.getWorld()).thenReturn(lobbyWorld);
            when(event.getBlock()).thenReturn(block);

            listener.onBlockExplode(event);

            verify(event).setCancelled(true);
        }

        @Test
        @DisplayName("a run under way explodes exactly as Minecraft intends")
        void allowsExplosionsWhileRunning() {
            when(lobby.state()).thenReturn(SpeedrunLobbyState.RUNNING);
            EntityExplodeEvent event = mock(EntityExplodeEvent.class);
            Location where = mock(Location.class);
            when(where.getWorld()).thenReturn(lobbyWorld);
            when(event.getLocation()).thenReturn(where);

            listener.onExplode(event);

            verify(event, never()).setCancelled(true);
        }

        @Test
        @DisplayName("with the setting off, a lobby creeper is allowed to do its worst")
        void allowsExplosionsWhenTurnedOff() {
            when(lobby.config()).thenReturn(config(true, false));
            EntityExplodeEvent event = mock(EntityExplodeEvent.class);
            Location where = mock(Location.class);
            when(where.getWorld()).thenReturn(lobbyWorld);
            when(event.getLocation()).thenReturn(where);

            listener.onExplode(event);

            verify(event, never()).setCancelled(true);
        }
    }

    /**
     * The two rules that reach past the lobby world: nobody mines and nothing hunts while the server
     * is waiting for a race. See {@code SpeedrunLobbySafetyListener.onBreak} for why server-wide.
     */
    @Nested
    @DisplayName("waiting for a run, anywhere on the server")
    class TheWait {

        private org.bukkit.event.block.BlockBreakEvent breakBy(Player player) {
            org.bukkit.event.block.BlockBreakEvent event =
                    mock(org.bukkit.event.block.BlockBreakEvent.class);
            when(event.getPlayer()).thenReturn(player);
            return event;
        }

        private org.bukkit.event.entity.EntityTargetEvent targeting(org.bukkit.entity.Entity mob,
                                                                    org.bukkit.entity.Entity target) {
            org.bukkit.event.entity.EntityTargetEvent event =
                    mock(org.bukkit.event.entity.EntityTargetEvent.class);
            when(event.getEntity()).thenReturn(mob);
            when(event.getTarget()).thenReturn(target);
            return event;
        }

        @Test
        @DisplayName("an ordinary player cannot break a block, in any world")
        void breakingIsRefusedBeforeARun() {
            World elsewhere = mock(World.class);
            when(elsewhere.getName()).thenReturn("somewhere-else");
            var event = breakBy(playerIn(elsewhere));

            listener.onBreak(event);

            verify(event).setCancelled(true);
        }

        @Test
        @DisplayName("somebody building the lobby is not stopped by this")
        void adminsMayStillBuild() {
            Player admin = playerIn(lobbyWorld);
            when(admin.hasPermission(de.raindancer.modules.speedrun.util.PermissionNodes.ADMIN))
                    .thenReturn(true);
            var event = breakBy(admin);

            listener.onBreak(event);

            verify(event, never()).setCancelled(true);
        }

        @Test
        @DisplayName("once the run is on, mining is the race")
        void breakingIsFineDuringARun() {
            when(lobby.state()).thenReturn(SpeedrunLobbyState.RUNNING);
            var event = breakBy(playerIn(lobbyWorld));

            listener.onBreak(event);

            verify(event, never()).setCancelled(true);
        }

        @Test
        @DisplayName("a host who wants a buildable lobby turns it back on")
        void breakingCanBeAllowedAgain() {
            when(lobby.config()).thenReturn(config(true, true, true, false));
            var event = breakBy(playerIn(lobbyWorld));

            listener.onBreak(event);

            verify(event, never()).setCancelled(true);
        }

        @Test
        @DisplayName("nothing picks a player as its target while the server waits")
        void monstersIgnorePlayers() {
            var event = targeting(mock(org.bukkit.entity.Zombie.class), playerIn(lobbyWorld));

            listener.onTarget(event);

            verify(event).setCancelled(true);
        }

        @Test
        @DisplayName("a mob going after another mob is nobody's business")
        void mobsMayStillFightEachOther() {
            var event = targeting(mock(org.bukkit.entity.Zombie.class),
                    mock(org.bukkit.entity.Villager.class));

            listener.onTarget(event);

            verify(event, never()).setCancelled(true);
        }

        @Test
        @DisplayName("once the run is on, everything hunts as always")
        void monstersHuntDuringARun() {
            when(lobby.state()).thenReturn(SpeedrunLobbyState.RUNNING);
            var event = targeting(mock(org.bukkit.entity.Zombie.class), playerIn(lobbyWorld));

            listener.onTarget(event);

            verify(event, never()).setCancelled(true);
        }

        @Test
        @DisplayName("a host who wants the wait dangerous turns it back on")
        void huntingCanBeAllowedAgain() {
            when(lobby.config()).thenReturn(config(true, true, false, true));
            var event = targeting(mock(org.bukkit.entity.Zombie.class), playerIn(lobbyWorld));

            listener.onTarget(event);

            verify(event, never()).setCancelled(true);
        }
    }
}
