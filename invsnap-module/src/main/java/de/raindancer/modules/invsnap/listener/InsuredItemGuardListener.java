package de.raindancer.modules.invsnap.listener;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.invsnap.model.ItemPolicy;
import de.raindancer.modules.invsnap.service.ItemInsuranceService;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;
import java.util.function.Function;

/**
 * What insurance covers on the ground: an insured item can be dropped like any other, but when its entity
 * would be destroyed — despawn, fire, lava, cactus, blasts, the void — the entity is removed and the stack goes
 * home to its owner, once. Wearing out ends the policy.
 */
public final class InsuredItemGuardListener implements IInvSnapListener {

    private final ItemInsuranceService insurance;
    private final Messages messages;
    private final Function<UUID, Player> online;
    private final ItemInsuranceJoinListener notices;

    public InsuredItemGuardListener(ItemInsuranceService insurance, Messages messages,
                                    Function<UUID, Player> online, ItemInsuranceJoinListener notices) {
        this.insurance = insurance;
        this.messages = messages;
        this.online = online;
        this.notices = notices;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDespawn(ItemDespawnEvent event) {
        if (rescue(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /** Any damage to an insured item entity counts as its destruction: fire, lava, cactus, blasts, lightning. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Item item && rescue(item)) {
            event.setCancelled(true);
        }
    }

    /** What is left: removed from the world by the void or a death/explosion that no damage event announced. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemove(EntityRemoveEvent event) {
        EntityRemoveEvent.Cause cause = event.getCause();
        if (event.getEntity() instanceof Item item && (cause == EntityRemoveEvent.Cause.OUT_OF_WORLD
                || cause == EntityRemoveEvent.Cause.DEATH || cause == EntityRemoveEvent.Cause.DESPAWN
                || cause == EntityRemoveEvent.Cause.EXPLODE)) {
            rescue(item);
        }
    }

    /** Sends the item's stack home and removes the entity; false when it is not insured or could not be secured. */
    private boolean rescue(Item item) {
        ItemStack stack = item.getItemStack();
        ItemPolicy policy = insurance.policyOf(stack).orElse(null);
        if (policy == null) {
            return false;
        }
        ItemInsuranceService.Placed placed = insurance.onDestroyed(item.getUniqueId(), stack);
        if (placed == ItemInsuranceService.Placed.NOT_SECURED) {
            return false;
        }
        item.remove();
        if (placed != ItemInsuranceService.Placed.ALREADY) {
            Player owner = online.apply(policy.owner());
            if (owner != null) {
                messages.send(owner, placed == ItemInsuranceService.Placed.INVENTORY
                        ? "invsnap.item.returned.inventory" : "invsnap.item.returned.list",
                        "item", policy.description());
            }
        }
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(PlayerItemBreakEvent event) {
        insurance.onBreak(event.getBrokenItem()).ifPresent(policy -> {
            Player owner = online.apply(policy.owner());
            if (owner != null) {
                notices.announce(owner);
            }
        });
    }

    @Override
    public void forget(UUID player) {
        // Nothing remembered: policies live in the store.
    }

    @Override
    public String describe() {
        return "insured items: a destroyed item entity goes home to its owner, wear ends the policy";
    }
}
