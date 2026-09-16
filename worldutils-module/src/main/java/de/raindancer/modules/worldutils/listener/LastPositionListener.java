package de.raindancer.modules.worldutils.listener;

import de.raindancer.modules.worldutils.WorldUtilsSettings;
import de.raindancer.modules.worldutils.store.LastPositions;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Notes where somebody was standing whenever they leave a world — by any teleport, any portal, or by
 * logging out — so {@code /w} can put them back there.
 *
 * <p>{@code MONITOR} and {@code ignoreCancelled}: only a move that actually happens is a place they
 * left. Portals are listened to separately because {@link PlayerPortalEvent} has handlers of its own,
 * and a teleport handler never hears about them.
 */
public final class LastPositionListener implements IWorldUtilsListener {

    private final LastPositions positions;
    private final Supplier<WorldUtilsSettings> settings;

    public LastPositionListener(LastPositions positions, Supplier<WorldUtilsSettings> settings) {
        this.positions = positions;
        this.settings = settings;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        leaving(event.getPlayer().getUniqueId(), event.getFrom(), event.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        leaving(event.getPlayer().getUniqueId(), event.getFrom(), event.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (settings.get().rememberLastPosition()) {
            positions.remember(event.getPlayer().getUniqueId(), event.getPlayer().getLocation());
        }
    }

    /** Remembers {@code from} when the move changes world; an in-world hop is not leaving anywhere. */
    void leaving(UUID player, Location from, Location to) {
        if (!settings.get().rememberLastPosition() || from == null || to == null
                || !from.isWorldLoaded() || !to.isWorldLoaded()
                || from.getWorld() == null || from.getWorld().equals(to.getWorld())) {
            return;
        }
        positions.remember(player, from);
    }

    @Override
    public void forget(UUID player) {
        // Nothing in memory: positions are Core's to keep.
    }

    @Override
    public String describe() {
        return "where each player last stood in each world";
    }
}
