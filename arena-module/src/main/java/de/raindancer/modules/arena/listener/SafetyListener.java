package de.raindancer.modules.arena.listener;

import de.raindancer.modules.arena.util.Builders;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.arena.ArenaServices;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.UUID;

/**
 * Nobody really dies in the arena, and outside a fight nobody is hurt at all.
 *
 * <p>A death is cancelled before anything else sees it — LOWEST — so none of what a death sets off happens: no drops,
 * no lost experience, no money lost on death, no death insurance charged, no death snapshot. Those all skip a
 * cancelled death. Falling off a map into the empty world below is the one way to be hurt in the hub, and it ends in
 * the hub too.
 */
public final class SafetyListener implements IArenaListener {

    private final ArenaServices services;

    public SafetyListener(ArenaServices services) {
        this.services = services;
    }

    private boolean arena(World world) {
        return world != null && services.arena().isArenaWorld(world.getName());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        if (!arena(player.getWorld())) {
            return;
        }
        event.setCancelled(true);
        heal(player);
        Scheduling.entity(services.plugin(), player, () -> toHub(player));
    }

    /** If something un-cancels the death after us, at least nothing drops and nothing is lost. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeathAnyway(PlayerDeathEvent event) {
        if (!event.isCancelled() && arena(event.getPlayer().getWorld())) {
            event.setKeepInventory(true);
            event.getDrops().clear();
            event.setKeepLevel(true);
            event.setDroppedExp(0);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || !arena(player.getWorld())
                || Builders.isBuilding(player)) {
            return;
        }
        if (services.fighting(player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        if (event.getCause() == EntityDamageEvent.DamageCause.VOID) {
            player.setFallDistance(0);
            toHub(player);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && arena(player.getWorld())
                && services.config().hubFed() && !services.fighting(player.getUniqueId())
                && event.getFoodLevel() < player.getFoodLevel()) {
            event.setCancelled(true);
        }
    }

    private void toHub(Player player) {
        services.arena().toHub(player);
    }

    private static void heal(Player player) {
        AttributeInstance most = player.getAttribute(Attribute.MAX_HEALTH);
        player.setHealth(most == null ? 20.0 : most.getValue());
        player.setFireTicks(0);
        player.setFreezeTicks(0);
        player.setFallDistance(0);
    }

    @Override
    public void forget(UUID player) {
        // Nothing is remembered per player here.
    }
}
