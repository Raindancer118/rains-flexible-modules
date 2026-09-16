package de.raindancer.modules.speedrun;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;

/**
 * Keeps a run's portal travel inside the run's own three worlds.
 *
 * <h2>The bug this exists for</h2>
 * A racer stepped into a nether portal in the speedrun world, and walked back out into the server's
 * own overworld — out of the race, into a world no reset ever touches, with the run still counting.
 * Minecraft links dimensions only for the primary level's own folder layout: a world created at
 * runtime has no nether and no end of its own, so travel out of it falls back to the server's, and
 * the trip back lands wherever <em>that</em> world's link points.
 *
 * <p>What Bukkit computed is not thrown away — only the world of it is corrected. The destination
 * coordinates it worked out are already right for the kind of dimension being entered, and the
 * world being swapped in is the same kind, so the scaling holds. Anything this module has no world
 * for (a datapack dimension, an unloaded one, a portal somewhere else entirely) is left exactly as
 * the server decided: a redirect that cannot be completed correctly is worse than none.
 *
 * @see SpeedrunWorlds
 */
public final class SpeedrunPortalListener implements Listener {

    private final SpeedrunLobby lobby;

    public SpeedrunPortalListener(SpeedrunLobby lobby) {
        this.lobby = lobby;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        if (event.isCancelled()) {
            return;   // ignoreCancelled only filters through the real event bus, not a direct call
        }
        // The correction itself is Core's WorldFamily#redirect, shared with every other plugin that
        // makes worlds at runtime; what stays here is which family is this module's.
        SpeedrunWorlds.around(lobby.config().worldName()).family()
                .redirect(event.getFrom().getWorld(), event.getTo(), Bukkit::getWorld)
                .ifPresent(event::setTo);
    }
}
