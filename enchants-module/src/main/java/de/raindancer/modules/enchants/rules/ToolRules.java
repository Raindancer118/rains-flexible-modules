package de.raindancer.modules.enchants.rules;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;

/** The arithmetic of the tool and elytra enchantments, kept apart from the server so it can be tested. */
public final class ToolRules {

    /** Steeper than this, up or down, and Excavator takes the floor or ceiling instead of a wall. */
    private static final float STEEP = 45f;
    /** How much harder than the block mined a neighbour may be and still come out with it. */
    private static final float HARDER_BY = 2f;
    /** A firework rocket pulls a glider towards 1.5 blocks a tick along the look. */
    private static final double ROCKET_SPEED = 1.5;

    private static final Map<Material, Material> SEEDS = Map.of(
            Material.WHEAT, Material.WHEAT_SEEDS,
            Material.CARROTS, Material.CARROT,
            Material.POTATOES, Material.POTATO,
            Material.BEETROOTS, Material.BEETROOT_SEEDS,
            Material.NETHER_WART, Material.NETHER_WART);

    private ToolRules() {
    }

    /** The eight neighbours of the mined block in the face the player looks at, as x, y, z offsets. */
    public static List<int[]> excavation(float pitch, float yaw) {
        List<int[]> offsets = new ArrayList<>(8);
        boolean flat = Math.abs(pitch) >= STEEP;
        float facing = ((yaw % 360) + 360) % 360;
        boolean alongZ = facing < 45 || facing >= 315 || (facing >= 135 && facing < 225);
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                if (a == 0 && b == 0) {
                    continue;
                }
                if (flat) {
                    offsets.add(new int[]{a, 0, b});
                } else if (alongZ) {
                    offsets.add(new int[]{a, b, 0});
                } else {
                    offsets.add(new int[]{0, b, a});
                }
            }
        }
        return offsets;
    }

    public static boolean excavates(float minedHardness, float neighbourHardness) {
        return neighbourHardness >= 0 && neighbourHardness <= minedHardness + HARDER_BY;
    }

    public static Optional<Material> seedOf(Material crop) {
        return Optional.ofNullable(SEEDS.get(crop));
    }

    /** Level I harvests the crop hit, II a 3x3 field around it, III a 5x5 one. */
    public static int harvestRadius(int level) {
        return Math.max(0, level - 1);
    }

    /** Experience with Wisdom: {@code percentPerLevel} more per level, the fraction rounded up by chance. */
    public static int wisdom(int experience, int level, int percentPerLevel, RandomGenerator random) {
        if (experience <= 0 || level <= 0) {
            return Math.max(0, experience);
        }
        double total = experience * (1 + level * percentPerLevel / 100.0);
        int whole = (int) Math.floor(total);
        return whole + (random.nextDouble() < total - whole ? 1 : 0);
    }

    /** Level I waits the full cooldown, II two thirds of it, III not at all. */
    public static long launchCooldownMillis(int level, int baseSeconds) {
        int clamped = Math.max(1, Math.min(3, level));
        return clamped == 3 ? 0 : baseSeconds * 1000L * (4 - clamped) / 3;
    }

    public static int soulboundAfterDeath(int level) {
        return Math.max(0, level - 1);
    }

    /**
     * One tick of a boost: the rocket's own formula, pulling the velocity towards the look at
     * {@code speedPercent} of a rocket's speed.
     *
     * @param velocity blocks per tick, x y z
     * @param look     a unit vector
     */
    public static double[] boostStep(double[] velocity, double[] look, int speedPercent) {
        double target = ROCKET_SPEED * speedPercent / 100.0;
        double[] next = new double[3];
        for (int i = 0; i < 3; i++) {
            next[i] = velocity[i] + look[i] * 0.1 + (look[i] * target - velocity[i]) * 0.5;
        }
        return next;
    }
}
