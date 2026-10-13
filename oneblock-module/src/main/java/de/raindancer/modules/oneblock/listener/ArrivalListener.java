package de.raindancer.modules.oneblock.listener;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.oneblock.OneBlockServices;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.UUID;

/**
 * On the island server: somebody has arrived — with an invite accepted elsewhere, or to go to their island. Handled a
 * second later, after BentoBox has put them wherever it puts somebody who joins, so its teleport does not land on top.
 */
public final class ArrivalListener implements IOneBlockListener {

    private static final long SETTLE_TICKS = 20L;

    private final OneBlockServices services;

    public ArrivalListener(OneBlockServices services) {
        this.services = services;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        UUID player = event.getPlayer().getUniqueId();
        Scheduling.entityLater(services.plugin(), event.getPlayer(), SETTLE_TICKS, () ->
                services.invites().ifPresent(invites -> invites.joined(player)));
    }

    /** Nothing kept here: an arrival waits in the invite service, which forgets it on its own. */
    @Override
    public void forget(UUID player) {
    }
}
