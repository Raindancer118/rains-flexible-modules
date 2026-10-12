package de.raindancer.modules.arena.model;

import java.util.Locale;

/** Every block from one corner to the other in one world, both corners included. */
public record Box(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public Box {
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("a box needs a world");
        }
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("a box's minimum is past its maximum — use Box.of");
        }
    }

    /** From two corners given either way round, as somebody standing at each would mark them. */
    public static Box of(String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        return new Box(world, Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    /** Whether a position is inside — a block counts whole, so x = maxX + 0.99 is still in. */
    public boolean contains(String inWorld, double x, double y, double z) {
        return inWorld != null && world.toLowerCase(Locale.ROOT).equals(inWorld.toLowerCase(Locale.ROOT))
                && x >= minX && x < maxX + 1 && y >= minY && y < maxY + 1 && z >= minZ && z < maxZ + 1;
    }

    public int width() {
        return maxX - minX + 1;
    }

    public int length() {
        return maxZ - minZ + 1;
    }

    public double centreX() {
        return (minX + maxX + 1) / 2.0;
    }

    public double centreZ() {
        return (minZ + maxZ + 1) / 2.0;
    }
}
