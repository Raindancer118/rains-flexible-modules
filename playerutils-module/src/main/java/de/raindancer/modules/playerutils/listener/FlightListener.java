package de.raindancer.modules.playerutils.listener;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.playerutils.PlayerUtilsServices;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.UUID;

/**
 * Puts given flight back every time the game takes it — always a tick later, because the game takes it while
 * these events are still being handed round, and setting it during them is undone by the game itself.
 */
public final class FlightListener implements IPlayerUtilsListener {

    private final PlayerUtilsServices services;

    public FlightListener(PlayerUtilsServices services) {
        this.services = services;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        later(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGamemode(PlayerGameModeChangeEvent event) {
        later(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent event) {
        later(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        later(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFall(EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL && event.getEntity() instanceof Player player
                && services.flight().landsSoftly(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    private void later(Player player) {
        Scheduling.entityLater(services.plugin(), player, 1L, () -> {
            if (player.isOnline() && services.flight().restore(player)) {
                services.messages().send(player, "playerutils.fly.kept");
            }
        });
    }

    @Override
    public void forget(UUID player) {
        services.flight().forget(player);
    }
}
