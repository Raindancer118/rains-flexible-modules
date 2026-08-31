package de.raindancer.modules.manhunt.service;

import de.raindancer.core.ui.messages.Messages;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * The waiting lobby's Bukkit half: puts a player who just joined a side into it, keeps combat out of
 * it, and lets a player back out of Adventure mode once they leave a side or a hunt actually starts.
 *
 * <h2>Why {@link #relocateIfWaiting} and {@link #releaseIfHeld} are plain methods, not event handlers</h2>
 * There is no Bukkit event for "joined a Manhunt side" — {@code ManhuntCommand.join()} and
 * {@code ManhuntLobbyMenu}'s join bands each call {@code teams.joinRunners}/{@code joinHunters}
 * directly already, the way every other side effect of joining in this module is wired, so both call
 * this listener directly too rather than this class guessing at the moment from an event it does not
 * have.
 *
 * <h2>Where the deciding happens</h2>
 * Nowhere here — see {@link ManhuntLobbyBox}'s own class javadoc. This class converts a Bukkit event
 * (or a direct call from a command/menu) into a question the box can answer, and acts on the answer.
 */
public final class ManhuntLobbyListener implements Listener {

    private final ManhuntLobbyBox box;
    private final Messages messages;

    public ManhuntLobbyListener(ManhuntLobbyBox box, Messages messages) {
        this.box = box;
        this.messages = messages;
    }

    /**
     * Called right after a player successfully joins either side.
     *
     * @param huntRunning whether a hunt is currently going — each call site already knows this
     *                    ({@code live.manhunt().isRunning()}), so this class does not need its own
     *                    reference to {@code ManhuntService} just to ask one question
     */
    public void relocateIfWaiting(Player player, boolean huntRunning) {
        if (huntRunning || !box.isActive()) {
            return;
        }
        box.spawnPoint().ifPresent(point -> {
            var world = player.getServer().getWorld(point.worldName());
            if (world == null) {
                return;
            }
            player.teleport(new Location(world, point.x(), point.y(), point.z(),
                    (float) box.spawnYaw(), 0f));
            // Adventure, not survival: it blocks both building and breaking for free, the same trick
            // hungergames-module's own LobbyListener uses for its glass lobby.
            player.setGameMode(GameMode.ADVENTURE);
        });
    }

    /** Called after a player leaves a side, or once a hunt actually starts — undoes the Adventure-mode
     *  hold, but only if this module is the reason they are still in it. */
    public void releaseIfHeld(Player player) {
        if (player.getGameMode() == GameMode.ADVENTURE) {
            player.setGameMode(GameMode.SURVIVAL);
        }
    }

    /**
     * A hit landing in or from the waiting lobby's box — cancelled outright, mirroring
     * {@code hungergames-module}'s own {@code LobbyListener.onDamage} exactly, including checking both
     * ends so nobody standing at the boundary can hit outward.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = attackerOf(event);
        if (attacker == null) {
            return;
        }
        if (box.forbidsCombatBetween(pointOf(attacker), pointOf(victim))) {
            event.setCancelled(true);
            messages.send(attacker, "manhunt.no-fighting-in-lobby");
        }
    }

    private static Player attackerOf(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player direct) {
            return direct;
        }
        if (event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }

    private static ManhuntLobbyBox.Point pointOf(Player player) {
        Location where = player.getLocation();
        String world = where.getWorld() == null ? "" : where.getWorld().getName();
        return new ManhuntLobbyBox.Point(world, where.getX(), where.getY(), where.getZ());
    }

    public String describe() {
        return "the waiting lobby: relocating a fresh joiner, and that nobody fights inside it";
    }
}
