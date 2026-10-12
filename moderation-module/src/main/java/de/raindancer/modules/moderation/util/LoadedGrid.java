package de.raindancer.modules.moderation.util;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;

/**
 * The world as far as it can be read without side effects: a chunk that is not loaded, or on Folia
 * belongs to another thread, reads as open air rather than being loaded or touched. For baits that
 * is the careful side — a vein next to the unknown is never shown.
 */
public final class LoadedGrid implements BlockGrid {

    private final World world;

    public LoadedGrid(World world) {
        this.world = world;
    }

    @Override
    public Material at(int x, int y, int z) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
            return Material.BEDROCK;
        }
        if (!world.isChunkLoaded(x >> 4, z >> 4) || !Bukkit.isOwnedByCurrentRegion(world, x >> 4, z >> 4)) {
            return Material.AIR;
        }
        return world.getBlockAt(x, y, z).getType();
    }

    @Override
    public boolean open(int x, int y, int z) {
        return WorldGrid.isOpen(at(x, y, z));
    }
}
