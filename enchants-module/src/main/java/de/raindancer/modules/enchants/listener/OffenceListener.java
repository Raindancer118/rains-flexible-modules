package de.raindancer.modules.enchants.listener;

import de.raindancer.modules.enchants.service.ArmourService;
import de.raindancer.modules.enchants.service.OffenceService;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;

/** Melee hits for the fighting enchantments; totems, fusion and the anvil for the chestplate ones. */
public final class OffenceListener implements IEnchantsListener {

    private final OffenceService offence;
    private final ArmourService armour;

    public OffenceListener(OffenceService offence, ArmourService armour) {
        this.offence = offence;
        this.armour = armour;
    }

    private static boolean melee(EntityDamageEvent.DamageCause cause) {
        return cause == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || cause == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player wearer) {
            Player hitter = event.getDamager() instanceof Player player ? player
                    : event.getDamager() instanceof Projectile shot && shot.getShooter() instanceof Player shooter
                    ? shooter : null;
            if (hitter != null && !hitter.equals(wearer)) {
                event.setDamage(event.getDamage() * armour.pvpFactor(wearer, event.getDamage()));
            }
        }
        if (offence.cleaving() || !melee(event.getCause()) || !(event.getDamager() instanceof Player attacker)
                || !(event.getEntity() instanceof LivingEntity target)) {
            return;
        }
        event.setDamage(offence.strike(attacker, target, event.getDamage()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLanded(EntityDamageByEntityEvent event) {
        if (offence.cleaving() || !melee(event.getCause()) || !(event.getDamager() instanceof Player attacker)
                || !(event.getEntity() instanceof LivingEntity target)) {
            return;
        }
        offence.landed(attacker, target, event.getFinalDamage());
    }

    /** No totem in hand: one kept in the chestplate may do instead. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDying(EntityResurrectEvent event) {
        if (event.isCancelled() && event.getEntity() instanceof Player player && armour.saveWithKeptTotem(player)) {
            event.setCancelled(false);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player
                && armour.keepTotems(player, event.getCursor(), event.getCurrentItem())) {
            event.setCancelled(true);
        }
    }

    /** The game drops an anvil's result from 40 levels on before anybody can step in; lifted here, capped below. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnvilOpen(org.bukkit.event.inventory.InventoryOpenEvent event) {
        if (event.getView() instanceof org.bukkit.inventory.view.AnvilView anvil) {
            anvil.setMaximumRepairCost(Integer.MAX_VALUE);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAnvil(PrepareAnvilEvent event) {
        armour.anvil(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTakeFused(InventoryClickEvent event) {
        if (event.getInventory() instanceof org.bukkit.inventory.AnvilInventory
                && event.getSlotType() == org.bukkit.event.inventory.InventoryType.SlotType.RESULT
                && event.getWhoClicked() instanceof Player player && ArmourService.fusions(event.getCurrentItem()) > 0) {
            armour.fusedBy(player, event.getCurrentItem());
        }
    }
}
