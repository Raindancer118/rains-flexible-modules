package de.raindancer.modules.enchants.listener;

import com.destroystokyo.paper.event.entity.EndermanEscapeEvent;
import de.raindancer.modules.enchants.service.ShardWorldService;
import de.raindancer.modules.enchants.service.ThorService;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Thor's throws and storms, and the shard world's rain sparing its endermen. */
public final class ThorListener implements IEnchantsListener {

    private final ThorService thor;
    private final ShardWorldService worlds;

    public ThorListener(ThorService thor, ShardWorldService worlds) {
        this.thor = thor;
        this.worlds = worlds;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onThrow(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_AIR && event.getHand() == EquipmentSlot.HAND
                && thor.throwHammer(event.getPlayer(), event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHit(ProjectileHitEvent event) {
        Location at = event.getHitEntity() != null ? event.getHitEntity().getLocation()
                : event.getHitBlock() != null ? event.getHitBlock().getLocation().add(0.5, 1, 0.5)
                : event.getEntity().getLocation();
        thor.hit(event.getEntity(), event.getHitEntity(), at);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRain(EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.DROWNING
                && ThorService.spared(event.getEntity(), worlds.isShardWorld(event.getEntity().getWorld()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlee(EndermanEscapeEvent event) {
        // Rain hurts an enderman, and the hurt — even one cancelled — sends it off as an indirect escape.
        boolean rain = event.getReason() == EndermanEscapeEvent.Reason.DROWN
                || event.getReason() == EndermanEscapeEvent.Reason.INDIRECT && event.getEntity().isInRain();
        if (rain && ThorService.spared(event.getEntity(), worlds.isShardWorld(event.getEntity().getWorld()))) {
            event.setCancelled(true);
        }
    }
}
