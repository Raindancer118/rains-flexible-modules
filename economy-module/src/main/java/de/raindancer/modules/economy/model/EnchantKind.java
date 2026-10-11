package de.raindancer.modules.economy.model;

/**
 * An enchantment the server knows, reduced to what pricing needs — read off the registry at the Bukkit line.
 *
 * @param id the key, lower case, without {@code minecraft:}
 */
public record EnchantKind(String id, int maxLevel, boolean treasure, boolean curse) {

    public EnchantKey at(int level) {
        return new EnchantKey(id, level);
    }
}
