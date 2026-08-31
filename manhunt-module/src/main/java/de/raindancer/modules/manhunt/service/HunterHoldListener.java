package de.raindancer.modules.manhunt.service;

import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The head start: freezes the Hunters in place for {@link ManhuntSettings#hunterReleaseDelaySeconds()}
 * after the Runners are already loose. Registered fresh per run and unregistered once the delay is up
 * — see {@code SpeedrunCountdown.onMove}, which this copies rather than reuses: that one freezes
 * whoever is racing during the countdown itself, this one freezes only the Hunter half, afterwards.
 */
final class HunterHoldListener implements Listener {

    private final Set<UUID> hunters;

    HunterHoldListener(Set<UUID> hunters) {
        this.hunters = Set.copyOf(Objects.requireNonNull(hunters, "hunters"));
    }

    /**
     * Blocks an actual step, not a look around, at {@code HIGHEST} — the last priority that can still
     * refuse the move before vanilla has already applied it.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!hunters.contains(event.getPlayer().getUniqueId())) {
            return;
        }
        if (event.getTo() == null || sameBlock(event.getFrom(), event.getTo())) {
            return;
        }
        event.setCancelled(true);
    }

    private static boolean sameBlock(Location from, Location to) {
        return from.getWorld() == to.getWorld()
                && from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ();
    }
}
