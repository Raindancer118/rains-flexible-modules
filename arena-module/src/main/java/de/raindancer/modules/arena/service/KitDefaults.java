package de.raindancer.modules.arena.service;

import de.raindancer.core.data.nbt.ItemText;
import de.raindancer.modules.arena.model.Kit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The kits a fresh arena starts with. Written to {@code kits.yml} once, only where the owner has no kit of that name,
 * so an owner's change to one of them is never put back.
 */
public final class KitDefaults {

    private final ItemText codec;

    public KitDefaults(ItemText codec) {
        this.codec = codec;
    }

    public List<Kit> all() {
        return List.of(gladiator(), knight(), netherite(), archer(), axe(), mace(), potion(), uhc(), sumo(), naval(), lancer(), rider());
    }

    private Kit gladiator() {
        Slots slots = new Slots().armour(Material.IRON_HELMET, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS,
                Material.IRON_BOOTS, 0);
        slots.hotbar(0, enchanted(Material.IRON_SWORD, Enchantment.SHARPNESS, 1));
        slots.hotbar(1, item(Material.COOKED_BEEF, 8));
        slots.hotbar(2, item(Material.GOLDEN_APPLE, 2));
        slots.offHand(item(Material.SHIELD, 1));
        return kit("gladiator", "Gladiator", Material.IRON_SWORD, slots, false);
    }

    private Kit knight() {
        Slots slots = new Slots().armour(Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS,
                Material.DIAMOND_BOOTS, 1);
        slots.hotbar(0, enchanted(Material.DIAMOND_SWORD, Enchantment.SHARPNESS, 2));
        slots.hotbar(1, item(Material.COOKED_BEEF, 8));
        slots.hotbar(2, item(Material.GOLDEN_APPLE, 3));
        slots.offHand(item(Material.SHIELD, 1));
        return kit("knight", "Knight", Material.DIAMOND_SWORD, slots, false);
    }

    private Kit netherite() {
        Slots slots = new Slots().armour(Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE,
                Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS, 4);
        slots.hotbar(0, enchanted(Material.NETHERITE_SWORD, Enchantment.SHARPNESS, 5));
        slots.hotbar(1, enchanted(Material.NETHERITE_AXE, Enchantment.SHARPNESS, 5));
        slots.hotbar(2, item(Material.COOKED_BEEF, 16));
        slots.hotbar(3, item(Material.GOLDEN_APPLE, 8));
        slots.hotbar(8, item(Material.TOTEM_OF_UNDYING, 1));
        slots.offHand(item(Material.SHIELD, 1));
        return kit("netherite", "Netherite", Material.NETHERITE_CHESTPLATE, slots, false);
    }

    private Kit archer() {
        Slots slots = new Slots();
        slots.wear(leather(Material.LEATHER_HELMET), leather(Material.LEATHER_CHESTPLATE),
                leather(Material.LEATHER_LEGGINGS), leather(Material.LEATHER_BOOTS));
        ItemStack bow = enchanted(Material.BOW, Enchantment.POWER, 3);
        bow.addUnsafeEnchantment(Enchantment.INFINITY, 1);
        slots.hotbar(0, bow);
        slots.hotbar(1, item(Material.STONE_SWORD, 1));
        slots.hotbar(2, item(Material.COOKED_BEEF, 8));
        slots.storage(0, item(Material.ARROW, 1));
        return kit("archer", "Archer", Material.BOW, slots, false);
    }

    private Kit axe() {
        Slots slots = new Slots().armour(Material.IRON_HELMET, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS,
                Material.IRON_BOOTS, 1);
        slots.hotbar(0, enchanted(Material.IRON_AXE, Enchantment.SHARPNESS, 1));
        slots.hotbar(1, item(Material.COOKED_BEEF, 8));
        slots.offHand(item(Material.SHIELD, 1));
        return kit("axe", "Axe & Shield", Material.IRON_AXE, slots, false);
    }

    private Kit mace() {
        Slots slots = new Slots().armour(Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS,
                Material.DIAMOND_BOOTS, 2);
        ItemStack mace = enchanted(Material.MACE, Enchantment.DENSITY, 3);
        mace.addUnsafeEnchantment(Enchantment.WIND_BURST, 1);
        slots.hotbar(0, mace);
        slots.hotbar(1, item(Material.WIND_CHARGE, 64));
        slots.hotbar(2, enchanted(Material.DIAMOND_SWORD, Enchantment.SHARPNESS, 2));
        slots.hotbar(3, item(Material.GOLDEN_APPLE, 3));
        slots.hotbar(4, item(Material.COOKED_BEEF, 8));
        return kit("mace", "Mace", Material.MACE, slots, false);
    }

    private Kit potion() {
        Slots slots = new Slots().armour(Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS,
                Material.DIAMOND_BOOTS, 2);
        slots.hotbar(0, enchanted(Material.DIAMOND_SWORD, Enchantment.SHARPNESS, 2));
        for (int slot = 1; slot <= 6; slot++) {
            slots.hotbar(slot, potion(Material.SPLASH_POTION, PotionType.STRONG_HEALING));
        }
        slots.hotbar(7, potion(Material.POTION, PotionType.SWIFTNESS));
        slots.hotbar(8, potion(Material.POTION, PotionType.STRENGTH));
        for (int slot = 0; slot < 12; slot++) {
            slots.storage(slot, potion(Material.SPLASH_POTION, PotionType.STRONG_HEALING));
        }
        slots.storage(12, item(Material.COOKED_BEEF, 16));
        return kit("potion", "Potions", Material.SPLASH_POTION, slots, false);
    }

    private Kit uhc() {
        Slots slots = new Slots();
        slots.wear(enchanted(Material.DIAMOND_HELMET, Enchantment.PROTECTION, 1), item(Material.IRON_CHESTPLATE, 1),
                enchanted(Material.DIAMOND_LEGGINGS, Enchantment.PROTECTION, 1), item(Material.IRON_BOOTS, 1));
        slots.hotbar(0, enchanted(Material.DIAMOND_SWORD, Enchantment.SHARPNESS, 1));
        slots.hotbar(1, enchanted(Material.BOW, Enchantment.POWER, 1));
        slots.hotbar(2, item(Material.FISHING_ROD, 1));
        slots.hotbar(3, item(Material.GOLDEN_APPLE, 4));
        slots.hotbar(4, item(Material.COOKED_BEEF, 32));
        slots.storage(0, item(Material.ARROW, 32));
        slots.offHand(item(Material.SHIELD, 1));
        return kit("uhc", "UHC", Material.GOLDEN_APPLE, slots, false);
    }

    private Kit sumo() {
        Slots slots = new Slots();
        slots.hotbar(0, enchanted(Material.STICK, Enchantment.KNOCKBACK, 1));
        return kit("sumo", "Sumo", Material.STICK, slots, true);
    }

    /** For a naval battle: a trident that comes back, one that flings you out of the water, spare boats. */
    private Kit naval() {
        Slots slots = new Slots();
        ItemStack helmet = leather(Material.LEATHER_HELMET);
        helmet.addUnsafeEnchantment(Enchantment.RESPIRATION, 3);
        ItemStack boots = leather(Material.LEATHER_BOOTS);
        boots.addUnsafeEnchantment(Enchantment.DEPTH_STRIDER, 3);
        slots.wear(helmet, leather(Material.LEATHER_CHESTPLATE), leather(Material.LEATHER_LEGGINGS), boots);
        slots.hotbar(0, enchanted(Material.TRIDENT, Enchantment.LOYALTY, 3));
        slots.hotbar(1, enchanted(Material.TRIDENT, Enchantment.RIPTIDE, 2));
        slots.hotbar(2, item(Material.COOKED_COD, 8));
        slots.hotbar(3, item(Material.OAK_BOAT, 2));
        slots.offHand(item(Material.SHIELD, 1));
        return kit("naval", "Naval", Material.TRIDENT, slots, false);
    }

    /** For mounted combat: a spear that lunges, the only way to steer a pig, and armour for the hits that land. */
    private Kit lancer() {
        Slots slots = new Slots().armour(Material.IRON_HELMET, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS,
                Material.IRON_BOOTS, 1);
        slots.hotbar(0, enchanted(Material.IRON_SPEAR, Enchantment.LUNGE, 2));
        ItemStack stick = item(Material.CARROT_ON_A_STICK, 1);
        stick.editMeta(meta -> meta.setUnbreakable(true));
        // In the off hand: a pig obeys it from either hand, so the spear stays ready while steering.
        slots.offHand(stick);
        slots.hotbar(1, item(Material.COOKED_PORKCHOP, 8));
        return kit("lancer", "Lancer", Material.IRON_SPEAR, slots, false);
    }

    /** For horseback combat: a spear that lunges and a shield — the horse does the rest. */
    private Kit rider() {
        Slots slots = new Slots().armour(Material.IRON_HELMET, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS,
                Material.IRON_BOOTS, 1);
        slots.hotbar(0, enchanted(Material.IRON_SPEAR, Enchantment.LUNGE, 2));
        slots.hotbar(1, item(Material.COOKED_BEEF, 8));
        slots.offHand(item(Material.SHIELD, 1));
        return kit("rider", "Rider", Material.SADDLE, slots, false);
    }

    // ── making items ───────────────────────────────────────────────────────────────────────────────

    private Kit kit(String id, String name, Material icon, Slots slots, boolean harmless) {
        List<String> encoded = new ArrayList<>(Kit.SLOTS);
        for (ItemStack item : slots.items) {
            String line = item == null ? null : codec.write(item);
            encoded.add(line == null ? "" : line);
        }
        return new Kit(id, name, icon.name(), encoded, List.of(), harmless);
    }

    private static ItemStack item(Material material, int amount) {
        ItemStack item = new ItemStack(material, amount);
        if (item.getType().getMaxDurability() > 0) {
            item.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
        }
        return item;
    }

    private static ItemStack enchanted(Material material, Enchantment enchantment, int level) {
        ItemStack item = item(material, 1);
        item.addUnsafeEnchantment(enchantment, level);
        return item;
    }

    private static ItemStack leather(Material material) {
        ItemStack item = enchanted(material, Enchantment.PROTECTION, 2);
        if (item.getItemMeta() instanceof LeatherArmorMeta meta) {
            meta.setColor(Color.fromRGB(0x7A, 0x4A, 0x2A));
            item.setItemMeta(meta);
        }
        return item;
    }

    private static ItemStack potion(Material material, PotionType type) {
        ItemStack item = new ItemStack(material);
        if (item.getItemMeta() instanceof PotionMeta meta) {
            meta.setBasePotionType(type);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** The 41 slots, filled by name rather than by number. */
    private static final class Slots {
        final List<ItemStack> items = new ArrayList<>(Collections.nCopies(Kit.SLOTS, null));

        Slots armour(Material helmet, Material chest, Material legs, Material boots, int protection) {
            return wear(protected_(helmet, protection), protected_(chest, protection), protected_(legs, protection),
                    protected_(boots, protection));
        }

        Slots wear(ItemStack helmet, ItemStack chest, ItemStack legs, ItemStack boots) {
            items.set(36, boots);
            items.set(37, legs);
            items.set(38, chest);
            items.set(39, helmet);
            return this;
        }

        void hotbar(int slot, ItemStack item) {
            items.set(slot, item);
        }

        void storage(int slot, ItemStack item) {
            items.set(9 + slot, item);
        }

        void offHand(ItemStack item) {
            items.set(40, item);
        }

        private static ItemStack protected_(Material material, int level) {
            return level > 0 ? enchanted(material, Enchantment.PROTECTION, level) : item(material, 1);
        }
    }
}
