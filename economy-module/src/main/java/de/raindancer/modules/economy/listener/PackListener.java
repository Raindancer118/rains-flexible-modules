package de.raindancer.modules.economy.listener;

import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.service.PackService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.UUID;

/** A right click with a pack unpacks it — and does nothing else, whatever block it was aimed at. */
public final class PackListener implements IEconomyListener {

    private final EconomyServices services;

    public PackListener(EconomyServices services) {
        this.services = services;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() == null || !PackService.isPack(event.getItem())
                || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        event.setCancelled(true);
        services.packs().unpack(event.getPlayer(), event.getHand());
    }

    @Override
    public void forget(UUID player) {
        // Remembers nobody.
    }
}
