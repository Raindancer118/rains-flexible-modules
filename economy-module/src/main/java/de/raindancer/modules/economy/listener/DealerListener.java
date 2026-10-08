package de.raindancer.modules.economy.listener;

import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.service.DealerService;
import de.raindancer.modules.economy.service.ScratchService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.UUID;

/** Sitting down at a dealer's table, scratching a ticket, and leaving tables in good order. */
public final class DealerListener implements IEconomyListener {

    private final EconomyServices services;

    public DealerListener(EconomyServices services) {
        this.services = services;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDealer(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        DealerService.gameOf(event.getRightClicked()).ifPresent(game -> {
            event.setCancelled(true);
            services.screens().table(event.getPlayer(), game);
        });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDealerAt(PlayerInteractAtEntityEvent event) {
        if (DealerService.gameOf(event.getRightClicked()).isPresent()) {
            event.setCancelled(true);
        }
    }

    /** Dealers placed before the suit get it when their chunk loads; a changed skin setting reaches them too. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onLoad(org.bukkit.event.world.EntitiesLoadEvent event) {
        for (org.bukkit.entity.Entity entity : event.getEntities()) {
            if (entity instanceof org.bukkit.entity.Mannequin dealer && DealerService.gameOf(dealer).isPresent()) {
                services.dealers().dress(dealer);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent event) {
        if (DealerService.gameOf(event.getEntity()).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onTicket(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)
                || !ScratchService.isTicket(event.getItem())) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        services.scratch().scratch(player, player.getInventory().getHeldItemSlot())
                .ifPresent(card -> services.screens().scratch(player, card));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        services.tables().leave(event.getPlayer());
        forget(event.getPlayer().getUniqueId());
    }

    @Override
    public void forget(UUID player) {
        services.tables().forget(player);
    }

    @Override
    public String describe() {
        return "dealers, scratch cards, and leaving tables in good order";
    }
}
