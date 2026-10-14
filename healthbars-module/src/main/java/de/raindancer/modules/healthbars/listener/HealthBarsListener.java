package de.raindancer.modules.healthbars.listener;

import de.raindancer.modules.healthbars.service.HealthBarService;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** A hit starts a mob's bar; a death or a quit ends one. */
public final class HealthBarsListener implements Listener {

    private final HealthBarService bars;

    public HealthBarsListener(HealthBarService bars) {
        this.bars = bars;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent event) {
        if (event.getEntity() instanceof LivingEntity living && !(living instanceof ArmorStand)) {
            bars.hit(living);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        bars.forget(event.getEntity().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        bars.forget(event.getPlayer().getUniqueId());
    }
}
