package de.raindancer.modules.rtp.listener;

import de.raindancer.modules.rtp.service.RtpService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/**
 * Letting go of somebody who has left.
 *
 * <h2>Why this module has only this one listener</h2>
 * The other things worth watching — walking off the block, being hurt mid-wait, logging out
 * part-way through a trip — are Core's {@code TravelListener}, and the module registers that rather
 * than writing a fourth copy of it. This is the one thing left over: the cooldown between goes, which
 * belongs to this module and knows nothing about players coming and going.
 *
 * <p>Without it the cooldown map keeps an entry for every player who has ever used {@code /rtp}. That
 * is a leak measured in months, and it is invisible: the server is a little larger every day and
 * nothing points at the cause.
 */
public final class RtpSessionListener implements IRtpListener {

    private final RtpService service;

    public RtpSessionListener(RtpService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    @Override
    public void forget(UUID player) {
        service.forget(player);
    }

    @Override
    public String describe() {
        return "letting go of a player's rtp cooldown when they leave";
    }
}
