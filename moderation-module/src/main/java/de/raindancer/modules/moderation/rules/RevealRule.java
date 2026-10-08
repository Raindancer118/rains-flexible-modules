package de.raindancer.modules.moderation.rules;

import de.raindancer.modules.moderation.util.BlockGrid;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;

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
