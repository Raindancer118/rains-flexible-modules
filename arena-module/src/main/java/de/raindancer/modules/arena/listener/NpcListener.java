package de.raindancer.modules.arena.listener;

import de.raindancer.modules.arena.ArenaServices;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.UUID;

/** A right click on a gladiator opens what that gladiator is for. */
public final class NpcListener implements IArenaListener {

    private final ArenaServices services;

    public NpcListener(ArenaServices services) {
        this.services = services;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        services.npcs().role(event.getRightClicked()).ifPresent(role -> {
            event.setCancelled(true);
            services.screens().forRole(event.getPlayer(), role);
        });
    }

    @Override
    public void forget(UUID player) {
        // Nothing is remembered per player here.
    }
}
