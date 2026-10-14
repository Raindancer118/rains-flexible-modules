package de.raindancer.modules.enchants.rules;

import java.util.ArrayList;
import java.util.List;

/** Volley: a thrown trident brings others along, fanned out to its sides like Multishot. */
public final class Volley {

    /** Degrees between two tridents of the fan. */
    private static final double SPREAD = 10;

    private Volley() {
    }

    /**
     * The directions of the extra tridents, as unit vectors: two per level, 10, 20 … degrees to either side of the
     * throw, turned about the vertical so a throw aimed up stays aimed up.
     */
    public static List<double[]> directions(double[] throwDirection, int level) {
        double length = Math.sqrt(throwDirection[0] * throwDirection[0] + throwDirection[1] * throwDirection[1]
                + throwDirection[2] * throwDirection[2]);
        double x = throwDirection[0] / length;
        double y = throwDirection[1] / length;
        double z = throwDirection[2] / length;
        List<double[]> directions = new ArrayList<>();
        for (int step = 1; step <= Math.max(1, Math.min(2, level)); step++) {
            for (int side : new int[]{-1, 1}) {
                double angle = Math.toRadians(side * step * SPREAD);
                double cos = Math.cos(angle);
                double sin = Math.sin(angle);
                directions.add(new double[]{x * cos - z * sin, y, x * sin + z * cos});
            }
        }
        return directions;
    }
}
