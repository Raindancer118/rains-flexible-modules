package de.raindancer.modules.economy.store;

import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Arrays;
import java.util.Objects;

/**
 * Which rewarding blocks a player put down themselves, kept in the chunk's own data — so mining an ore
 * you placed pays nothing, across restarts, without a file growing for ever. Only blocks that would pay
 * are marked, so a chunk carries a handful of numbers at most.
 */
public final class PlacedBlocks {

    public static final NamespacedKey KEY = Objects.requireNonNull(NamespacedKey.fromString("rainseconomy:placed"));

    private PlacedBlocks() {
    }

    /** A position within its chunk as one number. Y is offset so negative heights pack too. */
    public static long pack(int x, int y, int z) {
        return ((long) (y + 4096) << 8) | ((long) (x & 15) << 4) | (z & 15);
    }

    public static boolean contains(long[] packed, long position) {
        if (packed == null) {
            return false;
        }
        for (long each : packed) {
            if (each == position) {
                return true;
            }
        }
        return false;
    }

    public static long[] with(long[] packed, long position) {
        if (contains(packed, position)) {
            return packed;
        }
        long[] base = packed == null ? new long[0] : packed;
        long[] grown = Arrays.copyOf(base, base.length + 1);
        grown[base.length] = position;
        return grown;
    }

    public static long[] without(long[] packed, long position) {
        if (!contains(packed, position)) {
            return packed;
        }
        return Arrays.stream(packed).filter(each -> each != position).toArray();
    }

    /** Remembers that a player put this block here. On the block's own region thread. */
    public static void mark(Block block) {
        PersistentDataContainer data = block.getChunk().getPersistentDataContainer();
        long position = pack(block.getX(), block.getY(), block.getZ());
        data.set(KEY, PersistentDataType.LONG_ARRAY, with(data.get(KEY, PersistentDataType.LONG_ARRAY), position));
    }

    /** Whether a player put this block here; forgets it either way, since it is being broken. */
    public static boolean consume(Block block) {
        PersistentDataContainer data = block.getChunk().getPersistentDataContainer();
        long[] packed = data.get(KEY, PersistentDataType.LONG_ARRAY);
        long position = pack(block.getX(), block.getY(), block.getZ());
        if (!contains(packed, position)) {
            return false;
        }
        long[] left = without(packed, position);
        if (left.length == 0) {
            data.remove(KEY);
        } else {
            data.set(KEY, PersistentDataType.LONG_ARRAY, left);
        }
        return true;
    }
}
