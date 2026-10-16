package de.raindancer.modules.oneblock.store;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * An {@code /island} typed on another server that only the island server can do — {@code /island nether} from the
 * SMP — kept here while the player is moved over, and run once they arrive. A move takes seconds; anything older
 * than {@link #KEEP_MILLIS} was not followed by an arrival and is dropped.
 */
public final class PendingCommands {

    public static final long KEEP_MILLIS = 60_000L;

    private record Pending(String arguments, long until) {
    }

    private final Map<UUID, Pending> waiting = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public PendingCommands(LongSupplier clock) {
        this.clock = clock;
    }

    public void put(UUID player, String arguments) {
        if (arguments == null || arguments.isBlank()) {
            waiting.remove(player);
            return;
        }
        waiting.put(player, new Pending(arguments.strip(), clock.getAsLong() + KEEP_MILLIS));
    }

    /** What they typed, once; empty when nothing waits or it is too old. */
    public Optional<String> take(UUID player) {
        Pending pending = waiting.remove(player);
        return pending == null || clock.getAsLong() > pending.until() ? Optional.empty()
                : Optional.of(pending.arguments());
    }
}
