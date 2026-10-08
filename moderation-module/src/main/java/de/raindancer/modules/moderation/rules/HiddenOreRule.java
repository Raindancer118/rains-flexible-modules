package de.raindancer.modules.moderation.rules;

import de.raindancer.modules.moderation.util.BlockGrid;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * What lies hidden around a point: every deeply enclosed ore, each paired with a control — a deeply
 * enclosed block of plain rock at the same distance and the same height, only turned around the
 * vertical axis. "Deeply enclosed" is one rule for both: nothing open within {@link #DEPTH} blocks.
 *
 * <p>Pairing matters as much as the rule. Ore only forms at some heights and some distances; an
 * unpaired sample of rock would sit elsewhere, where turns happen to look less decisive, and an honest
 * miner would appear to favour the ore. Paired, ore and control are interchangeable to anybody who
 * cannot see through stone.
 *
 * <p>The rule has to be about the place, never about the block: excluding ore for being next to a
 * vein somebody already saw, but not rock, leaves more hidden ore far from tunnels than rock — and an
 * honest miner, who digs away from their old tunnels, would then seem to steer for ore. With one rule
 * for both, the two sets are interchangeable to anybody who cannot see through stone.
 */
public final class HiddenOreRule implements IModerationRule {

    /** Closer than this, a direction to a block says nothing. */
    public static final int NEAREST = 3;
    /** Nothing open within this many blocks, in any direction. */
    public static final int DEPTH = 2;
    private static final int ATTEMPTS = 24;

    /** Hidden ores and, index for index, their controls. */
    public record Census(List<int[]> ores, List<int[]> rocks) {
    }

    public Census around(BlockGrid grid, int[] from, int radius, Predicate<Material> watched, Predicate<Material> rock) {
        int reach = radius + DEPTH;
        int side = 2 * reach + 1;
        boolean[] open = new boolean[side * side * side];
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dy = -reach; dy <= reach; dy++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    open[index(dx + reach, dy + reach, dz + reach, side)] = grid.open(from[0] + dx, from[1] + dy, from[2] + dz);
                }
            }
        }
        List<int[]> ores = new ArrayList<>();
        List<int[]> rocks = new ArrayList<>();
        long max = (long) radius * radius;
        long min = (long) NEAREST * NEAREST;
        java.util.Random random = new java.util.Random(((long) from[0] * 73856093L) ^ ((long) from[1] * 19349663L)
                ^ ((long) from[2] * 83492791L));
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    long distance = (long) dx * dx + (long) dy * dy + (long) dz * dz;
                    if (distance > max || distance < min) {
                        continue;
                    }
                    Material type = grid.at(from[0] + dx, from[1] + dy, from[2] + dz);
                    if (type == null || !watched.test(type) || !deep(open, dx + reach, dy + reach, dz + reach, side)) {
                        continue;
                    }
                    int[] control = control(grid, open, from, dx, dy, dz, reach, side, rock, random);
                    if (control != null) {
                        ores.add(new int[]{from[0] + dx, from[1] + dy, from[2] + dz});
                        rocks.add(control);
                    }
                }
            }
        }
        return new Census(ores, rocks);
    }

    private static int[] control(BlockGrid grid, boolean[] open, int[] from, int dx, int dy, int dz, int reach, int side,
                                 Predicate<Material> rock, java.util.Random random) {
        double horizontal = Math.sqrt((double) dx * dx + (double) dz * dz);
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            int cx = (int) Math.round(horizontal * Math.cos(angle));
            int cz = (int) Math.round(horizontal * Math.sin(angle));
            if (Math.abs(cx) + reach >= side || Math.abs(cz) + reach >= side
                    || Math.abs(cx) > reach - DEPTH || Math.abs(cz) > reach - DEPTH) {
                continue;
            }
            Material type = grid.at(from[0] + cx, from[1] + dy, from[2] + cz);
            if (type == null || !rock.test(type) || !deep(open, cx + reach, dy + reach, cz + reach, side)) {
                continue;
            }
            return new int[]{from[0] + cx, from[1] + dy, from[2] + cz};
        }
        return null;
    }

    private static boolean deep(boolean[] open, int cx, int cy, int cz, int side) {
        for (int dx = -DEPTH; dx <= DEPTH; dx++) {
            for (int dy = -DEPTH; dy <= DEPTH; dy++) {
                for (int dz = -DEPTH; dz <= DEPTH; dz++) {
                    if (open[index(cx + dx, cy + dy, cz + dz, side)]) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static int index(int x, int y, int z, int side) {
        return (x * side + y) * side + z;
    }

    @Override
    public String describe() {
        return "what lies hidden around a point: ore, and plain rock to compare it with";
    }
}
