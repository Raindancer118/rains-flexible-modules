package de.raindancer.modules.enchants.rules;

import java.util.Optional;

/** Two Fusion chestplates in an anvil make the first one twice as strong, three times at most. */
public final class Fusion {

    public static final int MOST = 3;
    /** The game counts no more armour than this, nor more toughness than {@link #MOST_TOUGHNESS}. */
    public static final double MOST_ARMOUR = 30;
    public static final double MOST_TOUGHNESS = 20;

    private Fusion() {
    }

    /** The fusions a chestplate has after one more; empty when it has had all it can. */
    public static Optional<Integer> next(int fusions) {
        return fusions >= MOST ? Optional.empty() : Optional.of(fusions + 1);
    }

    public static double factor(int fusions) {
        return Math.pow(2, Math.clamp(fusions, 0, MOST));
    }

    /**
     * What a player's hit is multiplied by so a fused chestplate's extra armour counts half against it.
     *
     * @param armour      the wearer's armour, uncapped, with the fused chestplate; likewise toughness
     * @param extraArmour what fusing added to it; likewise extraToughness
     */
    public static double pvpDamageFactor(double damage, double armour, double toughness, double extraArmour,
                                         double extraToughness) {
        if (extraArmour <= 0 && extraToughness <= 0) {
            return 1.0;
        }
        double full = Offence.armourShare(damage, Math.min(armour, MOST_ARMOUR), Math.min(toughness, MOST_TOUGHNESS));
        double half = Offence.armourShare(damage, Math.min(armour - extraArmour / 2, MOST_ARMOUR),
                Math.min(toughness - extraToughness / 2, MOST_TOUGHNESS));
        return full >= 1 ? 1.0 : (1 - half) / (1 - full);
    }
}
