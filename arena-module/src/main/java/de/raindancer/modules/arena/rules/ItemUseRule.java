package de.raindancer.modules.arena.rules;

import java.util.Locale;
import java.util.Set;

/**
 * Which held items still work for a fighter whose crosshair is on a block. A bow must draw and a potion must drink
 * whatever is behind the target; but most of what a right click on a block does with an item changes something —
 * lights it, grows it, strips it, fills it, spawns or places a thing on it — and none of that belongs in an arena.
 *
 * <p>By name rather than {@code Material}, so it is decided without a server. A list of what is allowed rather than of
 * what is not: an item added by a new version is refused until somebody decides otherwise.
 */
public final class ItemUseRule implements IArenaRule {

    private static final Set<String> USABLE = Set.of(
            "AIR", "BOW", "CROSSBOW", "TRIDENT", "MACE", "SHIELD", "FISHING_ROD", "ENDER_PEARL", "SNOWBALL",
            "WIND_CHARGE", "TOTEM_OF_UNDYING", "MILK_BUCKET", "POTION", "SPLASH_POTION", "LINGERING_POTION",
            "GOLDEN_APPLE", "ENCHANTED_GOLDEN_APPLE", "GOLDEN_CARROT", "APPLE", "BREAD", "COOKED_BEEF",
            "COOKED_PORKCHOP", "COOKED_CHICKEN", "COOKED_MUTTON", "COOKED_RABBIT", "COOKED_COD", "COOKED_SALMON",
            "BAKED_POTATO", "PUMPKIN_PIE", "COOKIE", "MELON_SLICE", "CARROT", "SWEET_BERRIES", "DRIED_KELP",
            "HONEY_BOTTLE", "SUSPICIOUS_STEW", "MUSHROOM_STEW", "RABBIT_STEW", "BEETROOT_SOUP", "CHORUS_FRUIT",
            "FIREWORK_ROCKET", "SPYGLASS", "GOAT_HORN", "COMPASS", "RECOVERY_COMPASS", "CLOCK");

    public boolean usableAgainstBlocks(String material) {
        String name = material == null ? "AIR" : material.toUpperCase(Locale.ROOT);
        // Not swords or axes: a right click with either does nothing in a fight, and an axe's strips a log.
        return USABLE.contains(name) || name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE") || name.endsWith("_LEGGINGS")
                || name.endsWith("_BOOTS");
    }

    @Override
    public String describe() {
        return "only fighting gear, food and potions are used against a block";
    }
}
