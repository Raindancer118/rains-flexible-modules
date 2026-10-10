package de.raindancer.modules.economy.model;

/** One enchantment on an item being valued, reduced to what pricing needs. */
public record EnchantLevel(String key, int level, int maxLevel, boolean treasure, boolean curse) {
}
