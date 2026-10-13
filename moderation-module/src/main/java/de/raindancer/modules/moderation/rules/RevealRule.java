package de.raindancer.modules.moderation.rules;

import de.raindancer.modules.moderation.model.SeenOres;
import de.raindancer.modules.moderation.util.BlockGrid;

import org.bukkit.Material;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Which blocks a single break shows the player for the first time: neighbours of the broken block
 * that are solid and had no other open face. That is all an honest miner ever learns from digging,
 * and so the only blocks whose ore content chance alone decides.
 */
public final class RevealRule implements IModerationRule {

    private static final int[][] SIDES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    public record Revealed(int x, int y, int z, Material type) {
    }

    /** Called while the broken block still stands, as it does during the break event. */
    public List<Revealed> revealedBy(BlockGrid grid, int x, int y, int z) {
        List<Revealed> found = new ArrayList<>(6);
        for (int[] side : SIDES) {
            int nx = x + side[0];
            int ny = y + side[1];
            int nz = z + side[2];
            if (grid.open(nx, ny, nz)) {
                continue;
            }
            if (enclosedApartFrom(grid, nx, ny, nz, x, y, z)) {
                found.add(new Revealed(nx, ny, nz, grid.at(nx, ny, nz)));
            }
        }
        return found;
    }

    /** No natural vein is bigger; past this the walk gives up and the ore counts as hidden. */
    static final int MAX_VEIN = 64;
    /** Farther than this, nobody picks a diamond out of a cave wall. */
    static final double MAX_SIGHT = 64;
    private static final double SIGHT_STEP = 0.05;

    /**
     * Whether a revealed ore is a find chance alone decides: not touching ore the player already saw,
     * and not part of a vein with a face the player can see from {@code eye} — a cave wall, a ravine.
     * A vein seen from a cave was found by looking; digging out its hidden half proves nothing.
     *
     * <p>Open space alone is not enough: sealed air pockets, aquifers and lava lakes sit behind the rock
     * everywhere at diamond depth, and only an x-ray shows a vein touching one of them.
     */
    public boolean newVein(BlockGrid grid, Revealed revealed, SeenOres seen, Predicate<Material> sameKind, double[] eye) {
        if (seen.touches(revealed.x(), revealed.y(), revealed.z())) {
            return false;
        }
        return !visibleFrom(grid, revealed.x(), revealed.y(), revealed.z(), sameKind, eye);
    }

    private static boolean visibleFrom(BlockGrid grid, int x, int y, int z, Predicate<Material> sameKind, double[] eye) {
        Set<List<Integer>> visited = new HashSet<>();
        Deque<int[]> queue = new ArrayDeque<>();
        visited.add(List.of(x, y, z));
        queue.add(new int[]{x, y, z});
        while (!queue.isEmpty()) {
            int[] at = queue.poll();
            for (int[] side : SIDES) {
                int ox = at[0] + side[0];
                int oy = at[1] + side[1];
                int oz = at[2] + side[2];
                if (grid.open(ox, oy, oz) && inSight(grid, eye, ox, oy, oz)) {
                    return true;
                }
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int nx = at[0] + dx;
                        int ny = at[1] + dy;
                        int nz = at[2] + dz;
                        if (visited.size() >= MAX_VEIN || !sameKind.test(grid.at(nx, ny, nz))
                                || !visited.add(List.of(nx, ny, nz))) {
                            continue;
                        }
                        queue.add(new int[]{nx, ny, nz});
                    }
                }
            }
        }
        return false;
    }

    /** Whether nothing solid stands between the eye and the middle of the open cell {@code (cx, cy, cz)}. */
    static boolean inSight(BlockGrid grid, double[] eye, int cx, int cy, int cz) {
        double tx = cx + 0.5 - eye[0];
        double ty = cy + 0.5 - eye[1];
        double tz = cz + 0.5 - eye[2];
        double length = Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (length > MAX_SIGHT) {
            return false;
        }
        int ex = (int) Math.floor(eye[0]);
        int ey = (int) Math.floor(eye[1]);
        int ez = (int) Math.floor(eye[2]);
        int steps = (int) Math.ceil(length / SIGHT_STEP);
        for (int i = 1; i < steps; i++) {
            double f = (double) i / steps;
            int bx = (int) Math.floor(eye[0] + tx * f);
            int by = (int) Math.floor(eye[1] + ty * f);
            int bz = (int) Math.floor(eye[2] + tz * f);
            if (!(bx == cx && by == cy && bz == cz) && !(bx == ex && by == ey && bz == ez) && !grid.open(bx, by, bz)) {
                return false;
            }
        }
        return true;
    }

    /** Whether every face of a block is closed, not counting the one towards {@code (ex, ey, ez)}. */
    public static boolean enclosedApartFrom(BlockGrid grid, int x, int y, int z, int ex, int ey, int ez) {
        for (int[] side : SIDES) {
            int mx = x + side[0];
            int my = y + side[1];
            int mz = z + side[2];
            if (mx == ex && my == ey && mz == ez) {
                continue;
            }
            if (grid.open(mx, my, mz)) {
                return false;
            }
        }
        return true;
    }

    public static boolean enclosed(BlockGrid grid, int x, int y, int z) {
        return !grid.open(x, y, z) && enclosedApartFrom(grid, x, y, z, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);
    }

    @Override
    public String describe() {
        return "which blocks one break shows a miner for the first time";
    }
}
