package de.raindancer.modules.enchants.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Finds the inside of a portal frame the way the game shapes a nether portal: a flat rectangle of open
 * blocks, 2 to 21 wide and 3 to 21 high, walled by frame blocks on all four sides (corners optional).
 */
public final class PortalFrame {

    private static final int MIN_WIDTH = 2;
    private static final int MIN_HEIGHT = 3;
    private static final int MAX = 21;

    /** What the world looks like, without the world. */
    public interface Grid {
        boolean frame(int x, int y, int z);

        boolean open(int x, int y, int z);
    }

    /** The open blocks to fill, and whether the frame stands along the x axis (else along z). */
    public record Inside(boolean alongX, List<int[]> blocks) {
    }

    private PortalFrame() {
    }

    /** From any open block inside the frame. */
    /**
     * The blocks a hitbox {@code halfWidth} wide each way and {@code height} tall, standing at x y z, overlaps — what
     * the game asks when it decides somebody is in a portal. The block at the feet alone misses a player stepping
     * in from the side, whose hitbox is in the portal while their feet are still beside it.
     */
    public static java.util.List<int[]> touched(double x, double y, double z, double halfWidth, double height) {
        double inset = 1.0E-7;
        java.util.List<int[]> blocks = new java.util.ArrayList<>();
        for (int bx = (int) Math.floor(x - halfWidth); bx <= (int) Math.floor(x + halfWidth - inset); bx++) {
            for (int by = (int) Math.floor(y); by <= (int) Math.floor(y + height - inset); by++) {
                for (int bz = (int) Math.floor(z - halfWidth); bz <= (int) Math.floor(z + halfWidth - inset); bz++) {
                    blocks.add(new int[]{bx, by, bz});
                }
            }
        }
        return blocks;
    }

    public static Optional<Inside> find(Grid grid, int x, int y, int z) {
        Optional<Inside> alongX = find(grid, x, y, z, 1, 0);
        return alongX.isPresent() ? alongX : find(grid, x, y, z, 0, 1);
    }

    private static Optional<Inside> find(Grid grid, int x, int y, int z, int dx, int dz) {
        if (!grid.open(x, y, z)) {
            return Optional.empty();
        }
        int bottom = y;
        while (y - bottom < MAX && grid.open(x, bottom - 1, z)) {
            bottom--;
        }
        if (!grid.frame(x, bottom - 1, z)) {
            return Optional.empty();
        }
        int left = 0;
        while (left < MAX && grid.open(x - (left + 1) * dx, bottom, z - (left + 1) * dz)) {
            left++;
        }
        int right = 0;
        while (right < MAX && grid.open(x + (right + 1) * dx, bottom, z + (right + 1) * dz)) {
            right++;
        }
        int width = left + right + 1;
        int startX = x - left * dx;
        int startZ = z - left * dz;
        if (width < MIN_WIDTH || width > MAX
                || !grid.frame(startX - dx, bottom, startZ - dz)
                || !grid.frame(startX + width * dx, bottom, startZ + width * dz)) {
            return Optional.empty();
        }
        int height = 0;
        while (height <= MAX && grid.open(startX, bottom + height, startZ)) {
            height++;
        }
        if (height < MIN_HEIGHT || height > MAX) {
            return Optional.empty();
        }
        List<int[]> blocks = new ArrayList<>(width * height);
        for (int across = 0; across < width; across++) {
            int bx = startX + across * dx;
            int bz = startZ + across * dz;
            if (!grid.frame(bx, bottom - 1, bz) || !grid.frame(bx, bottom + height, bz)) {
                return Optional.empty();
            }
            for (int up = 0; up < height; up++) {
                if (!grid.open(bx, bottom + up, bz)) {
                    return Optional.empty();
                }
                blocks.add(new int[]{bx, bottom + up, bz});
            }
        }
        for (int up = 0; up < height; up++) {
            if (!grid.frame(startX - dx, bottom + up, startZ - dz)
                    || !grid.frame(startX + width * dx, bottom + up, startZ + width * dz)) {
                return Optional.empty();
            }
        }
        return Optional.of(new Inside(dx == 1, blocks));
    }
}
