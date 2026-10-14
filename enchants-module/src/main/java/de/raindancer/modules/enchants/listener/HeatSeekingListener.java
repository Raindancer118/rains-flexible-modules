package de.raindancer.modules.enchants.listener;

import de.raindancer.modules.enchants.service.HeatSeekingService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;

/** Heat-seeking: every projectile a player sends off, and what it hit. */
public final class HeatSeekingListener implements IEnchantsListener {

    private final HeatSeekingService heatSeeking;

    public HeatSeekingListener(HeatSeekingService heatSeeking) {
        this.heatSeeking = heatSeeking;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent event) {
        if (event.getEntity().getShooter() instanceof Player shooter) {
            heatSeeking.launched(shooter, event.getEntity());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHit(ProjectileHitEvent event) {
        heatSeeking.hit(event.getEntity(), event.getHitEntity());
    }
}
