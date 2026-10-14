package de.raindancer.modules.enchants.listener;

import de.raindancer.modules.enchants.service.VolleyService;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;

/** Volley: every trident a player throws, and where its copies land. */
public final class VolleyListener implements IEnchantsListener {

    private final VolleyService volley;

    public VolleyListener(VolleyService volley) {
        this.volley = volley;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent event) {
        if (event.getEntity() instanceof Trident trident && trident.getShooter() instanceof Player thrower) {
            volley.launched(thrower, trident);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHit(ProjectileHitEvent event) {
        if (event.getEntity() instanceof Trident trident) {
            volley.hit(trident);
        }
    }
}
