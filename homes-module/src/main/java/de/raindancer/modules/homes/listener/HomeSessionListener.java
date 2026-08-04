package de.raindancer.modules.homes.listener;

import de.raindancer.modules.homes.HomeServices;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/**
 * Letting go of somebody who has left.
 *
 * <h2>Why the module has only this one listener</h2>
 * Because the three things worth watching — walking off the block, being hurt mid-wait, and logging out
 * part-way through a teleport — are Core's {@code TravelListener}, and the module registers that rather
 * than writing a fourth copy of it. The old plugin had all of it here, identical to the teleport
 * requests' copy.
 *
 * <p>What is left over is the wait between teleports, which belongs to this module and knows nothing
 * about players coming and going. Without this, that map keeps an entry for every player who has ever
 * gone home on this server: a leak measured in months, invisible, with nothing pointing at the cause.
 */
public final class HomeSessionListener implements IHomeListener {

    private final HomeServices services;

    public HomeSessionListener(HomeServices services) {
        this.services = services;
    }

    /**
     * {@code MONITOR}: this decides nothing about the quit, it only notices.
     *
     * <p>Deliberately not "keep the wait in case they come back" — a wait somebody can end by
     * reconnecting is not a wait, and one they cannot end outlives them by a month.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    @Override
    public void forget(UUID player) {
        services.travelling().forget(player);
    }

    @Override
    public String describe() {
        return "letting go of a player's wait between homes when they leave";
    }
}
