package de.raindancer.modules.moderation.model;

/**
 * One block, where it was and what it was, at the moment somebody mined it.
 *
 * <h2>Why the material is a plain name and not Bukkit's own type</h2>
 * Kept alongside {@link MiningTrail}, for the same reason that class stays free of anything that
 * needs a server: a name is exactly as useful for comparing against a configured ore list, and it is
 * the difference between this being testable in a plain JUnit test and needing one that is not.
 */
public record MinedBlock(String world, int x, int y, int z, String material) {

    /** How far this block is from another — a straight line, treating each block as a single point. */
    public double distanceTo(MinedBlock other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
