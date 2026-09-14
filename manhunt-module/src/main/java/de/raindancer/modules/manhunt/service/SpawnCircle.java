package de.raindancer.modules.manhunt.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Where everybody stands when a hunt begins: evenly spaced around one circle, facing its middle.
 *
 * <h2>Why the circle is sized by the roster</h2>
 * Asked for directly — "depending on the count of people participating". A fixed radius either
 * packs a big roster shoulder to shoulder or scatters a small one out of sight of each other. So the
 * circle is exactly as large as it has to be for neighbours to stand {@code spacing} blocks apart, and
 * never smaller than {@code minRadius}, which keeps two or three players from spawning on top of the
 * centre and each other.
 *
 * <h2>Bukkit-free</h2>
 * Plain numbers in, plain numbers out, like every other decision in this module; {@code ManhuntService}
 * turns the spots into locations on the world's surface.
 */
public final class SpawnCircle {

    /** One place to stand, and the yaw that faces the middle from there. */
    public record Spot(double x, double z, float yaw) {
    }

    private SpawnCircle() {
    }

    /**
     * @param count     how many people need a place
     * @param spacing   the gap, in blocks, between neighbours on the circle
     * @param minRadius the smallest circle, however few people there are
     */
    public static List<Spot> around(double centreX, double centreZ, int count, double spacing, double minRadius) {
        if (count <= 0) {
            return List.of();
        }
        // Exactly spacing between neighbours, measured as the straight line between them — the chord —
        // rather than along the arc, because the straight line is the one players actually see.
        double needed = count == 1 ? 0 : spacing / (2 * Math.sin(Math.PI / count));
        double radius = Math.max(minRadius, needed);
        List<Spot> spots = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double angle = 2 * Math.PI * i / count;
            double x = centreX + radius * Math.cos(angle);
            double z = centreZ + radius * Math.sin(angle);
            spots.add(new Spot(x, z, yawTowards(centreX - x, centreZ - z)));
        }
        return List.copyOf(spots);
    }

    /** Minecraft's yaw for looking along {@code (dx, dz)}: 0 is +Z, 90 is -X. */
    private static float yawTowards(double dx, double dz) {
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }
}
