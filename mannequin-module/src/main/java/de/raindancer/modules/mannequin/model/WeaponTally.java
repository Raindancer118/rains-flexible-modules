package de.raindancer.modules.mannequin.model;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * One player's running total with one specific weapon, against one mannequin.
 *
 * <h2>Why a real {@code ItemStack} rides along, not just its {@code Material}</h2>
 * The leaderboard is meant to work the way a vanilla death message does — hovering the weapon's
 * name shows the actual item, name and enchants included, not a generic icon standing in for "a
 * netherite sword". {@link #sample} is a clone of the exact stack a player was holding on their
 * most recent hit with this material, kept only so a screen can show it as-is; nothing here reads
 * or writes it back onto any real item.
 */
public record WeaponTally(Material weapon, ItemStack sample, long hits, double totalDamage,
                          double highestHit) {

    public WeaponTally {
        if (weapon == null) {
            throw new IllegalArgumentException("a weapon tally needs a material");
        }
        sample = sample == null ? null : sample.clone();
        hits = Math.max(0, hits);
        totalDamage = Math.max(0.0, totalDamage);
        highestHit = Math.max(0.0, highestHit);
    }

    public static WeaponTally firstHit(Material weapon, ItemStack sample, double damage) {
        double clamped = Math.max(0.0, damage);
        return new WeaponTally(weapon, sample, 1, clamped, clamped);
    }

    /**
     * @param sample the weapon as it is right now — replaces the stored one, so a renamed or
     *               re-enchanted item shows as it currently is rather than as it was on the first hit
     */
    public WeaponTally hit(ItemStack sample, double damage) {
        double clamped = Math.max(0.0, damage);
        return new WeaponTally(weapon, sample, hits + 1, totalDamage + clamped,
                Math.max(highestHit, clamped));
    }

    public double averageDamage() {
        return hits == 0 ? 0.0 : totalDamage / hits;
    }
}
