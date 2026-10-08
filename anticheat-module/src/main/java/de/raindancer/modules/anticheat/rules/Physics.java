package de.raindancer.modules.anticheat.rules;

import org.bukkit.Material;

/** Vanilla movement constants and the few formulas every movement check shares. */
public final class Physics {

    public static final double GRAVITY = 0.08;
    public static final double VERTICAL_DRAG = 0.98;
    public static final double AIR_FRICTION = 0.91;
    public static final double DEFAULT_FRICTION = 0.6;
    public static final double JUMP_VELOCITY = 0.42;
    public static final double SPRINT_JUMP_BOOST = 0.2;
    public static final double AIR_ACCELERATION = 0.02;
    public static final double AIR_ACCELERATION_SPRINTING = 0.026;
    public static final double SPRINT_MULTIPLIER = 1.3;
    public static final double USING_ITEM_MULTIPLIER = 0.2;
    public static final double STEP_HEIGHT = 0.6;
    public static final double SLOW_FALLING_GRAVITY = 0.01;
    /** Vanilla zeroes any velocity component smaller than this at the start of a tick. */
    public static final double NEGLIGIBLE = 0.003;

    private Physics() {
    }

    /**
     * Vanilla only lands a player whose move ended against something below while not going up — a jump
     * passing a block top on the way up is still in the air. A step up keeps them on the ground.
     */
    public static boolean standing(boolean collisionBelow, double dy, boolean wasStanding, double stepHeight) {
        return collisionBelow && (dy <= NEGLIGIBLE || wasStanding && dy <= stepHeight + NEGLIGIBLE);
    }

    /** One tick of vertical motion in air: gravity, then drag. */
    public static double nextVertical(double velocity, double gravity) {
        return (velocity - gravity) * VERTICAL_DRAG;
    }

    /** How far {@code ticks} ticks move, starting at {@code firstVelocity} for the first of them. */
    public static double travelled(double firstVelocity, int ticks, double gravity) {
        double total = 0;
        double v = firstVelocity;
        for (int tick = 0; tick < Math.max(1, ticks); tick++) {
            total += v;
            v = nextVertical(v, gravity);
        }
        return total;
    }

    /** The acceleration a movement speed gives on ground of this friction. */
    public static double groundAcceleration(double movementSpeed, double blockFriction) {
        return movementSpeed * (0.21600002 / (blockFriction * blockFriction * blockFriction));
    }

    /** {@code jumpBoostLevel} 0 for none, 1 for Jump Boost I. */
    public static double jumpVelocity(double jumpStrength, int jumpBoostLevel, double blockJumpFactor) {
        return jumpStrength * blockJumpFactor + 0.1 * Math.max(0, jumpBoostLevel);
    }

    /** The block friction of what is underfoot. */
    public static double friction(Material material) {
        if (material == null) {
            return DEFAULT_FRICTION;
        }
        return switch (material) {
            case ICE, PACKED_ICE, FROSTED_ICE -> 0.98;
            case BLUE_ICE -> 0.989;
            case SLIME_BLOCK -> 0.8;
            default -> DEFAULT_FRICTION;
        };
    }

    /** Honey halves a jump; everything else leaves it alone. */
    public static double jumpFactor(Material material) {
        return material == Material.HONEY_BLOCK ? 0.5 : 1.0;
    }
}
