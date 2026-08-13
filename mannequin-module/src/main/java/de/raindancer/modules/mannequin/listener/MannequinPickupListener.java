package de.raindancer.modules.mannequin.listener;

import de.raindancer.modules.mannequin.service.MannequinPotionService;
import de.raindancer.modules.mannequin.store.MannequinRegistry;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.List;
import java.util.UUID;

/**
 * A potion dropped at a mannequin's feet is drunk, not carried.
 *
 * <h2>Composes with invincibility</h2>
 * {@link MannequinCombatListener} cancels every {@code EntityDamageEvent} regardless of cause, so a
 * Harming potion applied here cannot kill the mannequin either — its instant-damage effect fires
 * through the same damage event that invincibility already refuses. A beneficial potion works
 * exactly as intended; a harmful one is cosmetic. That composition is not tested against a live
 * server here, since it needs a running effect scheduler and a real damage tick to observe — it
 * follows directly from the two listeners' own, separately-tested behaviour instead.
 */
public final class MannequinPickupListener implements IMannequinListener {

    private final MannequinRegistry registry;
    private final MannequinPotionService potions;

    public MannequinPickupListener(MannequinRegistry registry, MannequinPotionService potions) {
        this.registry = registry;
        this.potions = potions;
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        if (registry.idFor(event.getEntity().getUniqueId()).isEmpty()) {
            return;
        }
        if (!(event.getEntity() instanceof org.bukkit.entity.Mannequin mannequin)) {
            return;
        }
        Item itemEntity = event.getItem();
        ItemStack stack = itemEntity.getItemStack();
        if (!isPotion(stack.getType())) {
            return;
        }

        event.setCancelled(true);
        List<PotionEffect> effects = potions.consume(stack);
        for (PotionEffect effect : effects) {
            mannequin.addPotionEffect(effect);
        }

        int amount = itemEntity.getItemStack().getAmount();
        if (amount <= 1) {
            itemEntity.remove();
        } else {
            stack.setAmount(amount - 1);
            itemEntity.setItemStack(stack);
        }
    }

    private static boolean isPotion(Material material) {
        return material == Material.POTION || material == Material.SPLASH_POTION
                || material == Material.LINGERING_POTION;
    }

    @Override
    public void forget(UUID player) {
        // Nothing per-player here either — a pickup is between the mannequin and the item entity.
    }

    @Override
    public String describe() {
        return "a mannequin drinking a potion dropped at its feet, rather than carrying it";
    }
}
