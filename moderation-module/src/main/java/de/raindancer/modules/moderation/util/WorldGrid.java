package de.raindancer.modules.moderation.util;

import org.bukkit.Material;
import org.bukkit.World;

/** The live world as a {@link BlockGrid}, read on the region thread that owns it. */
public final class WorldGrid implements BlockGrid {

    private final World world;

    public WorldGrid(World world) {
        this.world = world;
    }

    @Override
    public Material at(int x, int y, int z) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
            return Material.BEDROCK;
        }
        return world.getBlockAt(x, y, z).getType();
    }

    @Override
    public boolean open(int x, int y, int z) {
        return isOpen(at(x, y, z));
    }

    public static boolean isOpen(Material type) {
        return type == null || type.isAir() || !type.isOccluding();
    }
}
