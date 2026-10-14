package de.raindancer.modules.enchants.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Crystal Wither's build: the game's wither, of amethyst blocks instead of soul sand — a T of four with three
 * wither skeleton skulls on top, the two lower corners empty — standing along x or along z.
 */
public final class CrystalWither {

    /** What a block is, as far as the build cares. */
    public enum Part { AMETHYST, SKULL, AIR, OTHER }

    @FunctionalInterface
    public interface Grid {
        Part at(int x, int y, int z);
    }

    /**
     * @param base   the bottom amethyst block
     * @param blocks every block of the build, to clear when the wither rises
     */
    public record Found(int[] base, List<int[]> blocks) {
    }

    private CrystalWither() {
    }

    /** The build the skull just placed at x y z completes, if it completes one. */
    public static Optional<Found> find(Grid grid, int x, int y, int z) {
        for (int[] axis : new int[][]{{1, 0}, {0, 1}}) {
            for (int side = -1; side <= 1; side++) {
                int centreX = x - side * axis[0];
                int centreZ = z - side * axis[1];
                Optional<Found> found = at(grid, centreX, y - 2, centreZ, axis[0], axis[1]);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<Found> at(Grid grid, int baseX, int baseY, int baseZ, int dx, int dz) {
        List<int[]> blocks = new ArrayList<>();
        if (grid.at(baseX, baseY, baseZ) != Part.AMETHYST) {
            return Optional.empty();
        }
        blocks.add(new int[]{baseX, baseY, baseZ});
        for (int side = -1; side <= 1; side++) {
            int x = baseX + side * dx;
            int z = baseZ + side * dz;
            if (grid.at(x, baseY + 1, z) != Part.AMETHYST || grid.at(x, baseY + 2, z) != Part.SKULL) {
                return Optional.empty();
            }
            if (side != 0 && grid.at(x, baseY, z) != Part.AIR) {
                return Optional.empty();
            }
            blocks.add(new int[]{x, baseY + 1, z});
            blocks.add(new int[]{x, baseY + 2, z});
        }
        return Optional.of(new Found(new int[]{baseX, baseY, baseZ}, blocks));
    }
}
