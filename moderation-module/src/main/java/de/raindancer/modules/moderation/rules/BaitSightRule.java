package de.raindancer.modules.moderation.rules;

import de.raindancer.modules.moderation.util.BlockGrid;

import java.util.ArrayList;
import java.util.List;

/**
 * How close a tunnel may come to a bait ore before the bait has to go.
 *
 * <p>A client breaks a block the moment it digs it and tells the server afterwards. The server's
 * answer — the real block back where the bait was — arrives a ping later. A slow pickaxe is still
 * busy with the block next to the bait by then; an instant one (Efficiency V in netherrack) has
 * long broken it and shown the bait. So the faster the digging and the higher the ping, the further
 * from open space a bait must be taken back. Distances are steps along the axes, because each broken
 * block brings open space at most one such step closer.
 */
public final class BaitSightRule implements IModerationRule {

    /** Covers instant mining up to about 250 ms ping; beyond that a flash is possible again. */
    public static final int MAX_DISTANCE = 8;
    /** On top of the ping: up to a tick until the server handles the dig, and some jitter. */
    static final int SERVER_DELAY_MILLIS = 75;
    /** After a block that was not instant, vanilla waits five ticks before the next one starts. */
    private static final int DELAY_AFTER_DIG_TICKS = 5;
    private static final int[][] AXES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /** Dig progress per tick, as vanilla works it out (mining fatigue, water and air left out: they only slow it). */
    public static double progress(double toolSpeed, int hasteLevel, double breakSpeedAttribute, float hardness,
                                  boolean canHarvest) {
        if (hardness < 0) {
            return 0;
        }
        if (hardness == 0) {
            return Double.POSITIVE_INFINITY;
        }
        double speed = toolSpeed * (1 + 0.2 * Math.max(0, hasteLevel)) * breakSpeedAttribute;
        return speed / hardness / (canHarvest ? 30 : 100);
    }

    /**
     * The open-space distance at which a bait must already be gone: 1 means only a dig right next to
     * it, which the server always hears of in time when digging is slow.
     */
    public int distance(double progressPerTick, int pingMillis) {
        if (progressPerTick <= 0) {
            return 1;
        }
        boolean instant = progressPerTick >= 1;
        long digMillis = instant ? 0 : (long) Math.ceil(1 / progressPerTick) * 50;
        long perBlockMillis = instant ? 50 : digMillis + DELAY_AFTER_DIG_TICKS * 50L;
        long answer = Math.max(0, pingMillis) + SERVER_DELAY_MILLIS;
        if (answer < digMillis) {
            return 1;
        }
        long blocksMeanwhile = (answer - digMillis) / perBlockMillis + 1;
        return (int) Math.min(MAX_DISTANCE, 1 + blocksMeanwhile);
    }

    /** Steps to the nearest open block other than the block itself, or {@code limit + 1} when none is that near. */
    public static int openDistance(BlockGrid grid, int x, int y, int z, int limit) {
        for (int d = 1; d <= limit; d++) {
            for (int dx = -d; dx <= d; dx++) {
                int restY = d - Math.abs(dx);
                for (int dy = -restY; dy <= restY; dy++) {
                    int dz = restY - Math.abs(dy);
                    if (grid.open(x + dx, y + dy, z + dz) || dz != 0 && grid.open(x + dx, y + dy, z - dz)) {
                        return d;
                    }
                }
            }
        }
        return limit + 1;
    }

    /**
     * The closed blocks that come within {@code distance} of open space because {@code (x, y, z)} opens,
     * and were not that near before. At distance 1 these are exactly the blocks the dig reveals.
     * Called while the block still stands.
     *
     * <p>Runs on every dig, and a player can push the distance to its maximum by faking ping, so the
     * work is bounded whatever the cave looks like: every block within twice the distance is read
     * once, then one breadth-first pass from the open ones gives each block its distance to open space.
     */
    public List<int[]> newlyNear(BlockGrid grid, int x, int y, int z, int distance) {
        int reach = 2 * distance;
        int side = 2 * reach + 1;
        int[] steps = new int[side * side * side];
        java.util.Arrays.fill(steps, Integer.MAX_VALUE);
        int[] queue = new int[steps.length];
        int head = 0;
        int tail = 0;
        for (int dx = -reach; dx <= reach; dx++) {
            int restY = reach - Math.abs(dx);
            for (int dy = -restY; dy <= restY; dy++) {
                int restZ = restY - Math.abs(dy);
                for (int dz = -restZ; dz <= restZ; dz++) {
                    if ((dx != 0 || dy != 0 || dz != 0) && grid.open(x + dx, y + dy, z + dz)) {
                        int index = ((dx + reach) * side + dy + reach) * side + dz + reach;
                        steps[index] = 0;
                        queue[tail++] = index;
                    }
                }
            }
        }
        // Distances along the axes from the open blocks, as far as the given distance: no farther is needed.
        while (head < tail) {
            int index = queue[head++];
            int d = steps[index];
            if (d >= distance) {
                continue;
            }
            int ix = index / (side * side);
            int iy = index / side % side;
            int iz = index % side;
            for (int[] step : AXES) {
                int nx = ix + step[0];
                int ny = iy + step[1];
                int nz = iz + step[2];
                if (nx < 0 || ny < 0 || nz < 0 || nx >= side || ny >= side || nz >= side) {
                    continue;
                }
                int next = (nx * side + ny) * side + nz;
                if (steps[next] > d + 1) {
                    steps[next] = d + 1;
                    queue[tail++] = next;
                }
            }
        }
        List<int[]> near = new ArrayList<>();
        for (int dx = -distance; dx <= distance; dx++) {
            int restY = distance - Math.abs(dx);
            for (int dy = -restY; dy <= restY; dy++) {
                int restZ = restY - Math.abs(dy);
                for (int dz = -restZ; dz <= restZ; dz++) {
                    int index = ((dx + reach) * side + dy + reach) * side + dz + reach;
                    // Open blocks have 0; the dug block itself was never a source, so skip it by position.
                    if ((dx != 0 || dy != 0 || dz != 0) && steps[index] > distance) {
                        near.add(new int[]{x + dx, y + dy, z + dz});
                    }
                }
            }
        }
        return near;
    }

    @Override
    public String describe() {
        return "how close a tunnel may come to a bait ore before the bait goes";
    }
}
