package de.raindancer.modules.mannequin.service;

import de.raindancer.modules.mannequin.MannequinSettings;
import de.raindancer.modules.mannequin.model.ItemSpec;
import de.raindancer.modules.mannequin.rules.DurabilityRule;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Random;

/**
 * The only place a mannequin's equipment is ever written.
 *
 * <h2>The guarantee this class exists to keep</h2>
 * A mannequin's loadout must be structurally unobtainable: nothing here ever calls {@code
 * Inventory.addItem}, reaches through a {@code Player}'s inventory, or drops an item through {@code
 * HumanEntity}. Every write goes through {@link org.bukkit.inventory.EntityEquipment#setItem}
 * directly, which changes what the mannequin is wearing without ever placing the stack anywhere a
 * player could take it from. {@code MannequinEquipServiceTest} pins this with Mockito by asserting
 * that no such method is ever invoked on any mock.
 */
public final class MannequinEquipService implements IMannequinService {

    private final DurabilityRule durabilityRule;
    private volatile MannequinSettings settings;

    public MannequinEquipService(DurabilityRule durabilityRule, MannequinSettings settings) {
        this.durabilityRule = durabilityRule;
        this.settings = settings;
    }

    @Override
    public void settings(MannequinSettings settings) {
        // Nothing here currently reads a value from the settings, but the snapshot is kept anyway —
        // see IMannequinService and MODULE-LAYOUT.md on why a service with nothing to swap still
        // implements this rather than omitting it.
        this.settings = settings;
    }

    /**
     * Writes a slot directly. The {@code false} silent flag matters as much as the direct write:
     * true would fire the same equip-change event a player putting on armor fires, which some other
     * plugin could react to as if a player had done it.
     */
    public void apply(LivingEntity entity, EquipmentSlot slot, ItemStack stack) {
        if (entity == null || slot == null) {
            return;
        }
        entity.getEquipment().setItem(slot, stack, false);
    }

    /** A fresh, undamaged copy of exactly what the owner chose — never a copy of the worn item. */
    public void rebuildFromSpec(LivingEntity entity, EquipmentSlot slot, ItemSpec spec) {
        apply(entity, slot, spec == null ? null : spec.toItemStack());
    }

    /**
     * One by-player hit landing on an equipped piece: vanilla-style durability, Unbreaking
     * respected, and a rebuild rather than removal the instant it would break.
     *
     * @param spec the original loadout spec for this slot, so a broken piece comes back identical
     * @param rng  the source of randomness for the Unbreaking roll — injected so this is testable
     */
    public void damageEquippedPiece(LivingEntity entity, EquipmentSlot slot,
                                    ItemSpec spec, Random rng) {
        if (entity == null || slot == null || spec == null || rng == null) {
            return;
        }
        ItemStack current = entity.getEquipment().getItem(slot);
        if (current == null || current.getType().isAir()) {
            return;
        }
        int maxDurability = current.getType().getMaxDurability();
        if (maxDurability <= 0) {
            // Not a piece that can take durability damage at all.
            return;
        }
        ItemMeta meta = current.getItemMeta();
        if (!(meta instanceof Damageable damageable)) {
            return;
        }
        int unbreaking = current.getEnchantmentLevel(Enchantment.UNBREAKING);
        if (!durabilityRule.shouldTakeDamage(unbreaking, rng)) {
            return;
        }
        int nextDamage = damageable.getDamage() + 1;
        if (durabilityRule.wouldBreak(nextDamage, maxDurability)) {
            rebuildFromSpec(entity, slot, spec);
            return;
        }
        damageable.setDamage(nextDamage);
        current.setItemMeta(meta);
        apply(entity, slot, current);
    }
}
