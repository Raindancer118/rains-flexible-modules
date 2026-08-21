package de.raindancer.modules.rtp.listener;

import de.raindancer.modules.rtp.service.RtpService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/**
 * Letting go of somebody who has left — of what is finished, and only that.
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
 *
 * <h2>Why this does not simply drop the player's wait</h2>
 * It used to, and that was a hole: a player on cooldown could log out, log back in and go again
 * straight away, because the entry saying "not yet" had been thrown away on the way out. The map
 * still has to be bounded, so quitting now sweeps the waits that are already over — see
 * {@link RtpService#leaves}. A wait still running outlives the session it was earned in, which is
 * the whole point of a cooldown.
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
        service.leaves(player);
    }

    @Override
    public String describe() {
        return "clearing out finished rtp cooldowns when a player leaves";
    }
}
