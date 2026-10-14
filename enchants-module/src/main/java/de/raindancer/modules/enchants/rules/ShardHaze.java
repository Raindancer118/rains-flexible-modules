package de.raindancer.modules.enchants.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/** The haze hanging in the shard world: where its specks show around a player, and their colour. */
public final class ShardHaze {

    /** A dark red, nearly purple. */
    public static final int COLOUR = 0x5C0A33;
    private static final double BELOW = -1.0;
    private static final double ABOVE = 6.0;

    private ShardHaze() {
    }

    /** {@code count} specks around a player, as offsets x y z: within {@code radius}, from the feet to above the head. */
    public static List<double[]> specks(RandomGenerator random, int count, double radius) {
        List<double[]> specks = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            // sqrt: evenly over the disc, not crowded at the middle.
            double distance = radius * Math.sqrt(random.nextDouble());
            double angle = random.nextDouble() * Math.PI * 2;
            specks.add(new double[]{Math.cos(angle) * distance, BELOW + random.nextDouble() * (ABOVE - BELOW),
                    Math.sin(angle) * distance});
        }
        return specks;
    }
}
