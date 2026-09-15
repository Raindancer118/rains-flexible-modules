package de.raindancer.modules.speedrun;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import de.raindancer.modules.speedrun.util.PermissionNodes;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;

/**
 * The lobby as a safe room: while no run is under way, nobody standing in the lobby world can be
 * hurt, and nothing in it explodes.
 *
 * <h2>Why this exists at all</h2>
 * Asked for after a live round. Everybody waiting for a start is frozen in place by
 * {@link SpeedrunLobbyListener#onMove} and holding nothing but two UI items — which makes the wait
 * the one moment on the whole server where a player can neither fight back nor walk away. A creeper
 * wandering into that, or one racer deciding to swing at another, is not a hazard anybody chose; it
 * is a run that starts with somebody on two hearts, in a hole where the start line used to be.
 *
 * <h2>Why the two halves are two settings</h2>
 * They are different promises. "Nobody can be hurt" is about the people, and a host running a
 * deathmatch lobby might genuinely want it off; "nothing explodes" is about the map, and is what
 * keeps the start line, the signs and the lobby build itself standing. Neither implies the other,
 * so neither is inferred from the other — except for explosion <em>damage</em>, which is refused by
 * either one, because a host who said "nothing explodes in my lobby" has already answered the
 * question of whether the blast may hurt somebody.
 *
 * <h2>Why "no run under way" rather than READY alone</h2>
 * {@link SpeedrunLobbyState#COUNTDOWN} is the most defenceless moment of all, and
 * {@link SpeedrunLobbyState#FINISHED} is the crowd standing around the end of the last run waiting
 * for the next. Only {@link SpeedrunLobbyState#RUNNING} and {@link SpeedrunLobbyState#PAUSED} are a
 * race, and a race is played by Minecraft's own rules — see {@link SpeedrunCreeperOnBreakListener}
 * for a hazard this must never touch.
 *
 * <h2>Why the whole lobby world and not a radius</h2>
 * There is no lobby geometry to measure against. The lobby <em>is</em> the world — the same world
 * the run happens in, which is why the state check above is the entire fence, and why this can be
 * registered once for the life of the module rather than per session.
 */
public final class SpeedrunLobbySafetyListener implements Listener {

    private final SpeedrunLobby lobby;

    public SpeedrunLobbySafetyListener(SpeedrunLobby lobby) {
        this.lobby = lobby;
    }

    /**
     * Refuses harm to a player waiting in the lobby — from another player, from a mob, from a fall,
     * from anything. {@link EventPriority#LOW} so a plugin that genuinely wants the damage through
     * can still un-cancel it, and {@code ignoreCancelled} so this never argues with one that already
     * refused it.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || !betweenRuns(player.getWorld())) {
            return;
        }
        SpeedrunSettings current = lobby.config();
        if (current.lobbyProtected() || (current.lobbyExplosionsBlocked() && isBlast(event.getCause()))) {
            event.setCancelled(true);
        }
    }

    /**
     * Takes the fuse out of a creeper (or anything else about to go off) before it ever detonates —
     * the only one of these three that leaves nothing at all behind, not even the sound.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPrime(ExplosionPrimeEvent event) {
        if (explosionsAreRefused(event.getEntity().getWorld())) {
            event.setCancelled(true);
        }
    }

    /** The blast itself, for anything that got past {@link #onPrime} — a TNT minecart, say. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        if (explosionsAreRefused(event.getLocation().getWorld())) {
            event.setCancelled(true);
        }
    }

    /** A bed or a respawn anchor lit in the wrong dimension — a block explosion, with no entity. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (explosionsAreRefused(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    /**
     * Refuses an ordinary player breaking a block while no run is under way — <em>anywhere</em> on
     * the server, unlike the two handlers above.
     *
     * <h2>Why this one is not scoped to the lobby world</h2>
     * Asked for that way, and it is the only scope that means anything: the point is that the wait
     * before a race is not a head start, and a racer who can reach any other world in the wait can
     * mine there instead. The module already assumes a server built around this one lobby — see
     * {@link SpeedrunLobbyListener}'s own note on why its join handler reaches server-wide.
     *
     * <p>Anybody holding {@link PermissionNodes#ADMIN} is exempt: somebody has to be able to build
     * the lobby, fix the start line, and clear whatever the last round left, and all of that happens
     * in exactly the state this refuses.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (lobby.config().breakingBlocksBeforeRuns() || running()) {
            return;
        }
        if (event.getPlayer().hasPermission(PermissionNodes.ADMIN)) {
            return;
        }
        event.setCancelled(true);
    }

    /**
     * Takes every mob's attention off every player while no run is under way, anywhere on the server
     * — the same reach and the same reasoning as {@link #onBreak}.
     *
     * <p>By the <em>target</em> rather than by the mob's type: a phantom and a ghast are not
     * {@code Monster}s in Bukkit's own hierarchy, and an angry wolf is not one either, yet all three
     * make the wait a fight. What they have in common is the thing worth checking — they picked a
     * player.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTarget(EntityTargetEvent event) {
        if (lobby.config().monstersHuntBeforeRuns() || running()) {
            return;
        }
        if (event.getTarget() instanceof Player && !(event.getEntity() instanceof Player)) {
            event.setCancelled(true);
        }
    }

    /** Whether a race is actually being played right now — the fence every rule here sits behind. */
    private boolean running() {
        SpeedrunLobbyState state = lobby.state();
        return state == SpeedrunLobbyState.RUNNING || state == SpeedrunLobbyState.PAUSED;
    }

    private boolean explosionsAreRefused(World world) {
        return betweenRuns(world) && lobby.config().lobbyExplosionsBlocked();
    }

    /** Whether {@code world} is the lobby's own and there is no race going on in it right now. */
    private boolean betweenRuns(World world) {
        if (world == null || !world.getName().equals(lobby.config().worldName())) {
            return false;
        }
        return !running();
    }

    /** Whether this damage came out of an explosion, by either of the two causes Bukkit has for one. */
    private static boolean isBlast(EntityDamageEvent.DamageCause cause) {
        return cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                || cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION;
    }
}
