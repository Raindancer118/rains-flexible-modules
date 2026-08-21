package de.raindancer.modules.warp.listener;

import de.raindancer.modules.warp.WarpServices;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/**
 * Letting go of somebody who has left.
 *
 * <h2>Why the module has only this one listener</h2>
 * Because the other three things worth watching — walking off the block, being hurt mid-wait, and
 * logging out part-way through a warp — are Core's {@code TravelListener}, and the module registers
 * that rather than writing a fourth copy of it. This is the one thing left over: the warp cooldown,
 * which belongs to this module's own {@code WarpRegistry} and knows nothing about players coming
 * and going.
 *
 * <p>Without it the cooldown map keeps an entry for every player who has ever warped on this server.
 * That is a leak measured in months, and it is invisible: the server is a little larger every day
 * and nothing points at the cause.
 */
public final class WarpSessionListener implements IWarpListener {

    private final WarpServices services;

    public WarpSessionListener(WarpServices services) {
        this.services = services;
    }

    /**
     * {@code MONITOR}: this decides nothing about the quit, it only notices.
     *
     * <p>Their warm-up is Core's {@code TravelListener}'s to drop; this is only the wait between
     * warps. Deliberately not "keep it in case they come back" — a wait somebody can end by
     * reconnecting is a wait, and one they cannot is a wait that outlives them by a month.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    @Override
    public void forget(UUID player) {
        services.catalogue().leaves(player);
    }

    @Override
    public String describe() {
        return "letting go of a player's warp cooldown when they leave";
    }
}
