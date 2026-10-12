package de.raindancer.modules.enchants.listener;

import de.raindancer.modules.enchants.service.AirService;
import de.raindancer.modules.enchants.service.NightVisionService;
import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;

import java.util.UUID;

/** Flight, Double Jump and Night Vision reacting to what a player does and wears. */
public final class AirListener implements IEnchantsListener {

    private final AirService air;
    private final NightVisionService nightVision;

    public AirListener(AirService air, NightVisionService nightVision) {
        this.air = air;
        this.nightVision = nightVision;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        if (air.onToggleFlight(event.getPlayer(), event.isFlying())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onArmour(PlayerArmorChangeEvent event) {
        nightVision.sync(event.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        air.join(event.getPlayer());
        nightVision.sync(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        air.leave(event.getPlayer());
    }

    @Override
    public void forget(UUID player) {
        // The AirService owns the per-player state and lets go of it in leave().
    }
}
