package de.raindancer.modules.enchants.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Thor: an axe or mace thrown like Mjölnir, lightning where it hits, and a trident that calls a storm where it lands.
 * What a level does, and where the storm's bolts strike. Also who the shard world's rain leaves alone.
 */
public final class Thor {

    private Thor() {
    }

    private static int clamp(int level) {
        return Math.max(1, Math.min(3, level));
    }

    /** What a thrown hammer deals to what it hits, in half-hearts — III far more than the rest. */
    public static double hammerDamage(int level) {
        return clamp(level) == 3 ? 40 : 6 + 2 * clamp(level);
    }

    /** Ticks before the hammer can be thrown again — the time it takes to come back, more or less. */
    public static int cooldownTicks(int level) {
        return 70 - 15 * clamp(level);
    }

    /** Bolts of a trident's storm. */
    public static int stormBolts(int level) {
        return 1 + 2 * clamp(level);
    }

    /** How far around the trident they strike, in blocks. */
    public static double stormRadius(int level) {
        return 2 + clamp(level);
    }

    /** What one bolt deals to whatever stands where it strikes, in half-hearts. */
    public static double stormDamage(int level) {
        return clamp(level) == 3 ? 16 : 3 + clamp(level);
    }

    /** Where the storm's bolts strike, as x z offsets from the trident: the first on it, the rest around it. */
    public static List<double[]> boltSpots(RandomGenerator random, int level) {
        List<double[]> spots = new ArrayList<>();
        spots.add(new double[]{0.0, 0.0});
        double radius = stormRadius(level);
        for (int i = 1; i < stormBolts(level); i++) {
            double distance = radius * Math.sqrt(random.nextDouble());
            double angle = random.nextDouble() * Math.PI * 2;
            spots.add(new double[]{Math.cos(angle) * distance, Math.sin(angle) * distance});
        }
        return spots;
    }

    /** Whether the rain spares an enderman: in the shard world, where it nearly always rains, unless it stands in water. */
    public static boolean sparesEnderman(boolean inShardWorld, boolean inWater) {
        return inShardWorld && !inWater;
    }
}
