package de.raindancer.modules.moderation.model;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Ore blocks a player has seen or dug lately. A newly revealed ore touching one of these is the same
 * vein carrying on, which honest miners expect and follow — never a new find, never a secret target.
 */
public final class SeenOres {

    private static final int CAPACITY = 1024;
    private final Set<Long> positions = new HashSet<>();
    private final Deque<Long> order = new ArrayDeque<>();

    public synchronized void add(int x, int y, int z) {
        long key = key(x, y, z);
        if (positions.add(key)) {
            order.addLast(key);
            if (order.size() > CAPACITY) {
                positions.remove(order.removeFirst());
            }
        }
    }

    public synchronized boolean contains(int x, int y, int z) {
        return positions.contains(key(x, y, z));
    }

    /** Whether it or any of its 26 neighbours has been seen. */
    public synchronized boolean touches(int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (positions.contains(key(x + dx, y + dy, z + dz))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public synchronized void clear() {
        positions.clear();
        order.clear();
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
    }
}
