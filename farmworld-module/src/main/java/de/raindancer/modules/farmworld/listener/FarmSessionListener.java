package de.raindancer.modules.farmworld.listener;

import de.raindancer.modules.farmworld.FarmWorldServices;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/**
 * Letting go of somebody who has left.
 *
 * <h2>Why the module has only this one listener</h2>
 * Because everything else worth watching already belongs to somebody. Walking off the block, being hurt
 * mid-wait and logging out part-way through a trip are Core's {@code TravelListener}, which the module
 * registers rather than writing a fourth copy of. A portal lit inside a farm world leading to the farm
 * nether rather than the main one is Core's {@code FarmWorldPortalListener}, which Core registers itself —
 * and has to, because a server with the farm worlds in Core and the portal linking in a module would have
 * farm worlds whose portals quietly led out of them.
 *
 * <p>This is the one thing left over: the wait between trips, which is the module's own and knows nothing
 * about players coming and going.
 *
 * <p>Without it the wait is remembered for every player who has ever entered a farm world. That is a leak
 * measured in months and it is invisible: the server is a little larger every day and nothing points at
 * the cause.
 */
public final class FarmSessionListener implements IFarmWorldListener {

    private final FarmWorldServices services;

    public FarmSessionListener(FarmWorldServices services) {
        this.services = services;
    }

    /**
     * {@code MONITOR}: this decides nothing about the quit, it only notices.
     *
     * <p>Their warm-up is Core's {@code TravelListener}'s to drop; this is only the wait between trips.
     * Deliberately not "keep it in case they come back" — a wait somebody can end by reconnecting is a
     * wait, and one they cannot is a wait that outlives them by a month.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    @Override
    public void forget(UUID player) {
        services.travelling().leaves(player);
    }

    @Override
    public String describe() {
        return "letting go of a player's wait between farm world trips when they leave";
    }
}
