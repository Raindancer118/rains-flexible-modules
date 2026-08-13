package de.raindancer.modules.mannequin.rules;

import java.util.Random;

/**
 * Vanilla's own Unbreaking chance, as a pure and testable function.
 *
 * <h2>The formula</h2>
 * Minecraft gives an item with Unbreaking level {@code n} a {@code 1/(n+1)} chance of actually
 * losing a durability point on any hit that would otherwise damage it — level 0 (no enchant) always
 * takes it, level 3 takes it a quarter of the time. Implemented here as {@code rng.nextInt(n + 1) ==
 * 0}, which is exactly a one-in-{@code (n+1)} chance and matches vanilla's own dice roll.
 */
public final class DurabilityRule implements IMannequinRule {

    /**
     * @param unbreakingLevel the piece's Unbreaking level, 0 when it has none
     * @param rng             injected rather than a static {@code Random}, so a test can seed it or
     *                        substitute a fixed sequence
     */
    public boolean shouldTakeDamage(int unbreakingLevel, Random rng) {
        if (rng == null) {
            throw new IllegalArgumentException("a durability roll needs a source of randomness");
        }
        if (unbreakingLevel <= 0) {
            return true;
        }
        return rng.nextInt(unbreakingLevel + 1) == 0;
    }

    /** Whether accumulated damage has reached the point the item would break. */
    public boolean wouldBreak(int accumulatedDamage, int maxDurability) {
        return maxDurability > 0 && accumulatedDamage >= maxDurability;
    }

    @Override
    public String describe() {
        return "vanilla's 1/(unbreaking+1) chance of a hit actually costing a durability point";
    }
}
