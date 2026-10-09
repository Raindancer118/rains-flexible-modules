package de.raindancer.modules.economy.listener;

import de.raindancer.modules.economy.EconomyServices;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.UUID;

/** Deaths, for what dying costs when the owner has made it cost something. */
public final class SupplyListener implements IEconomyListener {

    private final EconomyServices services;

    public SupplyListener(EconomyServices services) {
        this.services = services;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        var player = event.getPlayer();
        services.death().died(player.getUniqueId(), player.getWorld().getName(), player);
    }

    @Override
    public void forget(UUID player) {
        // Nothing is remembered about a player.
    }

    @Override
    public String describe() {
        return "what dying costs";
    }
}
