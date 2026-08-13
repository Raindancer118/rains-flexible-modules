package de.raindancer.modules.mannequin.model;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What an owner chose for one equipment slot in the loadout screen: a material and a set of
 * enchantments, kept apart from the live {@link org.bukkit.inventory.ItemStack} the mannequin is
 * actually wearing.
 *
 * <h2>Why this exists separately from the live stack</h2>
 * The live stack takes durability damage and eventually breaks. When it does, {@code
 * MannequinEquipService} has to rebuild a fresh copy identical to what the owner originally
 * chose — not whatever is left of the damaged one. This is that original choice, unaffected by
 * anything that happens to the item in the world.
 *
 * <h2>Why enchants are applied unsafely</h2>
 * The loadout screen deliberately allows combinations vanilla refuses — Sharpness and Smite
 * together, an enchant above its usual maximum level — because a training dummy is not equipment
 * anybody can loot back out, so the usual "would this be obtainable in survival" guard does not
 * apply. {@link #toItemStack()} therefore always uses {@link ItemStack#addUnsafeEnchantment}.
 */
public record ItemSpec(Material material, Map<Enchantment, Integer> enchants) {

    public ItemSpec {
        if (material == null) {
            throw new IllegalArgumentException("an item spec needs a material");
        }
        enchants = enchants == null ? Map.of() : Map.copyOf(enchants);
    }

    public static ItemSpec of(Material material) {
        return new ItemSpec(material, Map.of());
    }

    /** The same material, with one more (or replaced) enchant level. */
    public ItemSpec withEnchant(Enchantment enchant, int level) {
        Map<Enchantment, Integer> next = new LinkedHashMap<>(enchants);
        if (level <= 0) {
            next.remove(enchant);
        } else {
            next.put(enchant, level);
        }
        return new ItemSpec(material, next);
    }

    /**
     * A fresh, undamaged stack built exactly from this spec — never a copy of a worn item.
     *
     * <p>Deliberately never calls {@code ItemStack#getItemMeta()}: {@link ItemStack#addUnsafeEnchantment}
     * writes straight into the bare stack's own enchantment table without needing the server's item
     * factory, which is what keeps this method callable from a plain unit test as well as a live one.
     */
    public ItemStack toItemStack() {
        ItemStack stack = new ItemStack(material);
        for (Map.Entry<Enchantment, Integer> entry : enchants.entrySet()) {
            stack.addUnsafeEnchantment(entry.getKey(), entry.getValue());
        }
        return stack;
    }
}
