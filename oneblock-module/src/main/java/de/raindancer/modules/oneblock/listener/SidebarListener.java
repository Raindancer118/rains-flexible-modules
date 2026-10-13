package de.raindancer.modules.oneblock.listener;

import de.raindancer.modules.oneblock.OneBlockServices;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/** On the island server: who leaves takes their sidebar and their cached balance with them. */
public final class SidebarListener implements IOneBlockListener {

    private final OneBlockServices services;

    public SidebarListener(OneBlockServices services) {
        this.services = services;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    @Override
    public void forget(UUID player) {
        services.sidebar().ifPresent(sidebar -> sidebar.forget(player));
    }
}
