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

    public void forget(UUID player) {
        found.remove(player);
    }
}
