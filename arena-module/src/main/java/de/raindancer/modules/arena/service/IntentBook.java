package de.raindancer.modules.arena.service;

import de.raindancer.modules.arena.api.MatchSpec;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * What somebody on their way over from the survival server wants once they are here — the queue, a mob run, an agreed
 * fight. Told before the proxy moves them, picked up at their join, forgotten after two minutes if they never come.
 */
public final class IntentBook {

    public enum Kind { HUB, QUEUE, WAVES, AGREED }

    /** @param agreed the agreed fight's id, for {@link Kind#AGREED} */
    public record Intent(Kind kind, MatchSpec spec, String agreed, long expires) {
    }

    private static final long LIFETIME = 120_000;

    private final Map<UUID, Intent> waiting = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public IntentBook(LongSupplier clock) {
        this.clock = clock;
    }

    public void put(UUID player, Kind kind, MatchSpec spec, String agreed) {
        waiting.put(player, new Intent(kind, spec, agreed, clock.getAsLong() + LIFETIME));
    }

    /** The intent, removed as it is handed out. */
    public Optional<Intent> take(UUID player) {
        Intent intent = waiting.remove(player);
        return intent == null || intent.expires() < clock.getAsLong() ? Optional.empty() : Optional.of(intent);
    }

    public void sweep() {
        long now = clock.getAsLong();
        waiting.values().removeIf(intent -> intent.expires() < now);
    }
}
