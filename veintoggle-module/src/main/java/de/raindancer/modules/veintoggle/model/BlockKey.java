package de.raindancer.modules.veintoggle.model;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.UUID;

/** Where a block is, without holding the world or the chunk. */
public record BlockKey(UUID world, int x, int y, int z) {

    public static BlockKey of(Block block) {
        return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }

    /** @return null for a location without a world */
    public static BlockKey of(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        return new BlockKey(location.getWorld().getUID(), location.getBlockX(), location.getBlockY(),
                location.getBlockZ());
    }

    public Location centre(World in) {
        return new Location(in, x + 0.5, y + 0.5, z + 0.5);
    }

    public double distance(BlockKey other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
