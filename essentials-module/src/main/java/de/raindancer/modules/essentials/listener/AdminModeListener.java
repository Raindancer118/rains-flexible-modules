package de.raindancer.modules.essentials.listener;

import de.raindancer.modules.essentials.EssentialsServices;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.UUID;

/** Dying in admin mode drops nothing: admin tools must not end up on the floor for anybody to pick up. */
public final class AdminModeListener implements IEssentialsListener {

    private final EssentialsServices services;

    public AdminModeListener(EssentialsServices services) {
        this.services = services;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        if (!services.adminMode().isInAdminMode(event.getPlayer().getUniqueId())) {
            return;
        }
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setKeepLevel(true);
        event.setDroppedExp(0);
    }

    @Override
    public void forget(UUID player) {
        // Holds nothing per player; the service does.
    }
}
