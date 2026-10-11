package de.raindancer.modules.anticheat.rules;

import java.util.Locale;

/**
 * Passing through solid blocks between two positions. The inside check looks at where a move ends; a
 * hack that clips two blocks in one packet ends in the open on the far side of a wall. Vanilla stops a
 * level move at the first solid block, so every box along it must be free — judged only on moves that
 * do not change height, because stepping up a block's corner legitimately passes through its edge.
 */
public final class PhaseRule implements IAntiCheatRule {

    /** Whether anything solid is in a box, the way the game's collision asks. */
    @FunctionalInterface
    public interface Space {
        boolean solid(double minX, double minY, double minZ, double maxX, double maxY, double maxZ);
    }

    /** A move shorter than this is left to the end-point check. */
    public static final double LONGEST_UNSWEPT = 0.5;
    /** Rises and falls up to this are level: a hair of float noise on the way. */
    public static final double LEVEL = 0.01;
    private static final double STEP = 0.1;
    private static final double SHRINK = 0.06;

    public Judgement judge(Space space, double fromX, double fromY, double fromZ, double toX, double toY, double toZ,
                           double width, double height) {
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        double distance = Math.hypot(dx, dz);
        if (Math.abs(toY - fromY) > LEVEL || distance <= LONGEST_UNSWEPT) {
            return Judgement.PASS;
        }
        double half = width / 2 - SHRINK;
        int steps = (int) Math.ceil(distance / STEP);
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            double x = fromX + dx * t;
            double z = fromZ + dz * t;
            if (space.solid(x - half, fromY + SHRINK, z - half, x + half, fromY + height - SHRINK, z + half)) {
                return Judgement.fail(distance * (1 - t), String.format(Locale.ROOT,
                        "moved %.1f blocks through a solid block", distance));
            }
        }
        return Judgement.PASS;
    }

    @Override
    public String describe() {
        return "whether a level move passed through a solid block on the way";
    }
}
