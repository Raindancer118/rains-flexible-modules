package de.raindancer.modules.claims;


import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Turns a {@link ClaimShape} into the column outline used by both the particle and the block renderer. */
public final class OutlineGeometry {

    private OutlineGeometry() {
    }

    /**
     * Walks every polygon edge and returns the block columns it passes through, every
     * {@code spacing} blocks. Corners are always included.
     */
    public static List<ClaimPoint> outlineColumns(ClaimShape shape, int spacing) {
        int step = Math.max(1, spacing);
        Set<ClaimPoint> columns = new LinkedHashSet<>();
        List<ClaimPoint> vertices = shape.vertices();
        int size = vertices.size();
        for (int index = 0; index < size; index++) {
            ClaimPoint from = vertices.get(index);
            ClaimPoint to = vertices.get((index + 1) % size);
            columns.add(from);
            appendLine(columns, from, to, step);
        }
        return new ArrayList<>(columns);
    }

    /** Corners only — they get the highlight block and the vertical pillars. */
    public static List<ClaimPoint> corners(ClaimShape shape) {
        return shape.vertices();
    }

    /**
     * The outline as an unbroken chain of orthogonally adjacent columns.
     * <p>
     * {@link #outlineColumns} interpolates and therefore steps diagonally on slanted edges. That is fine
     * for particles, but a fence built on it can never close: fences and walls only connect north, east,
     * south and west, so two diagonally offset posts are simply not neighbours. This walk moves one axis
     * at a time, inserting the extra column at every diagonal step, which is what makes a polygon fence
     * actually join up around a corner.
     */
    public static List<ClaimPoint> connectedRing(ClaimShape shape) {
        Set<ClaimPoint> ring = new LinkedHashSet<>();
        List<ClaimPoint> vertices = shape.vertices();
        int size = vertices.size();
        for (int index = 0; index < size; index++) {
            appendConnectedLine(ring, vertices.get(index), vertices.get((index + 1) % size));
        }
        return new ArrayList<>(ring);
    }

    /**
     * Bresenham that never moves both axes in the same step, so the result is 4-connected.
     * <p>
     * The {@code else if} is the whole trick: plain Bresenham advances x and z together on a 45° line and
     * leaves diagonal gaps behind.
     */
    private static void appendConnectedLine(Set<ClaimPoint> target, ClaimPoint from, ClaimPoint to) {
        int x = from.x();
        int z = from.z();
        int dx = Math.abs(to.x() - x);
        int dz = Math.abs(to.z() - z);
        int stepX = Integer.signum(to.x() - x);
        int stepZ = Integer.signum(to.z() - z);
        int error = dx - dz;

        target.add(new ClaimPoint(x, z));
        // Bounded so a corrupt shape can never spin here forever.
        int guard = (dx + dz) * 2 + 8;
        while ((x != to.x() || z != to.z()) && guard-- > 0) {
            int doubled = 2 * error;
            if (doubled > -dz && x != to.x()) {
                error -= dz;
                x += stepX;
            } else if (z != to.z()) {
                error += dx;
                z += stepZ;
            } else {
                break;
            }
            target.add(new ClaimPoint(x, z));
        }
    }

    private static void appendLine(Set<ClaimPoint> target, ClaimPoint from, ClaimPoint to, int spacing) {
        int dx = to.x() - from.x();
        int dz = to.z() - from.z();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        if (steps == 0) {
            return;
        }
        for (int index = spacing; index < steps; index += spacing) {
            double t = (double) index / steps;
            int x = from.x() + (int) Math.round(dx * t);
            int z = from.z() + (int) Math.round(dz * t);
            target.add(new ClaimPoint(x, z));
        }
        target.add(to);
    }

    /** Y values at which a horizontal ring is drawn, given where the viewer is standing. */
    public static List<Integer> ringHeights(ClaimShape shape, int viewerY, int maxRings) {
        List<Integer> heights = new ArrayList<>();
        heights.add(shape.minY());
        if (shape.maxY() != shape.minY()) {
            heights.add(shape.maxY());
        }
        int clampedViewer = Math.max(shape.minY(), Math.min(shape.maxY(), viewerY));
        if (!heights.contains(clampedViewer)) {
            heights.add(clampedViewer);
        }
        // Eye level reads much better than foot level when standing inside the claim.
        int eyeLevel = Math.max(shape.minY(), Math.min(shape.maxY(), viewerY + 1));
        if (!heights.contains(eyeLevel)) {
            heights.add(eyeLevel);
        }
        while (heights.size() > Math.max(1, maxRings)) {
            heights.remove(heights.size() - 1);
        }
        return heights;
    }

    /** Vertical pillar Y values for a corner column. */
    public static List<Integer> pillarHeights(ClaimShape shape, int viewerY, int radius, int spacing) {
        List<Integer> heights = new ArrayList<>();
        int step = Math.max(1, spacing);
        int from = Math.max(shape.minY(), viewerY - radius);
        int to = Math.min(shape.maxY(), viewerY + radius);
        for (int y = from; y <= to; y += step) {
            heights.add(y);
        }
        if (!heights.contains(shape.minY()) && shape.minY() >= from - step && shape.minY() <= to) {
            heights.add(shape.minY());
        }
        if (!heights.contains(shape.maxY()) && shape.maxY() >= from && shape.maxY() <= to + step) {
            heights.add(shape.maxY());
        }
        return heights;
    }
}
