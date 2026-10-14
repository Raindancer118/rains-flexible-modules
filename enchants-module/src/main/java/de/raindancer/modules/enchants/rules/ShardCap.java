package de.raindancer.modules.enchants.rules;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** At most so many shards per player in any hour, so a farm in the eternal night cannot print them. */
public final class ShardCap {

    private static final long HOUR = 3_600_000L;

    private final LongSupplier clock;
    private final Map<UUID, Deque<Long>> found = new ConcurrentHashMap<>();

    public ShardCap(LongSupplier clock) {
        this.clock = clock;
    }

    /** Counts one more shard if the hour has room for it. */
    public boolean take(UUID player, int perHour) {
        if (perHour <= 0) {
            // No limit set: nothing to count either.
            return true;
        }
        long now = clock.getAsLong();
        Deque<Long> times = found.computeIfAbsent(player, id -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() >= HOUR) {
                times.pollFirst();
            }
            if (times.size() >= perHour) {
                return false;
            }
            times.addLast(now);
            return true;
        }
    }

    /**
     * Lets go of a player who left — only once their hour is over: forgetting it sooner would hand a full hour's
     * shards to anybody who logs out and back in.
     */
    public void forget(UUID player) {
        long now = clock.getAsLong();
        found.computeIfPresent(player, (id, times) -> {
            synchronized (times) {
                while (!times.isEmpty() && now - times.peekFirst() >= HOUR) {
                    times.pollFirst();
                }
                return times.isEmpty() ? null : times;
            }
        });
    }
}
