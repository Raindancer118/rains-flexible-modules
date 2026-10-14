package de.raindancer.modules.enchants.listener;

import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import de.raindancer.modules.enchants.service.ElytraService;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.UUID;

/**
 * Boost and Launch. A right click in the air only reaches the server with something in hand, so a left
 * click boosts too; a firework rocket in hand is left to do its own job.
 */
public final class ElytraListener implements IEnchantsListener {

    private final ElytraService elytra;

    public ElytraListener(ElytraService elytra) {
        this.elytra = elytra;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.LEFT_CLICK_AIR)) {
            return;
        }
        if (event.getAction() == Action.RIGHT_CLICK_AIR && event.getItem() != null
                && event.getItem().getType() == Material.FIREWORK_ROCKET) {
            return;
        }
        elytra.click(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onJump(PlayerJumpEvent event) {
        elytra.jump(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player
                && (event.getCause() == EntityDamageEvent.DamageCause.FLY_INTO_WALL
                || event.getCause() == EntityDamageEvent.DamageCause.FALL)
                && elytra.shielded(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    @Override
    public void forget(UUID player) {
        elytra.forget(player);
    }
}
