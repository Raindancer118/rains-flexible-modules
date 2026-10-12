package de.raindancer.modules.arenagate.service;

import de.raindancer.modules.arena.api.MatchSpec;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Invites waiting for an answer on this server: between two players here, or from somebody in the arena to somebody
 * here ({@code fromArena}). A minute each.
 */
public final class GateInvites {

    public record Pending(String id, UUID from, String fromName, UUID to, MatchSpec spec, boolean fromArena,
                          long expires) {
    }

    private static final long LIFETIME = 60_000;

    private final Map<String, Pending> open = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public GateInvites(LongSupplier clock) {
        this.clock = clock;
    }

    public Pending add(String id, UUID from, String fromName, UUID to, MatchSpec spec, boolean fromArena) {
        Pending pending = new Pending(id, from, fromName, to, spec, fromArena, clock.getAsLong() + LIFETIME);
        open.put(id, pending);
        return pending;
    }

    public List<Pending> forPlayer(UUID player) {
        long now = clock.getAsLong();
        open.values().removeIf(pending -> pending.expires() < now);
        return open.values().stream().filter(pending -> pending.to().equals(player)).toList();
    }

    /** The invite meant, removed: the one named, or the latest. */
    public Optional<Pending> take(UUID player, String id) {
        List<Pending> mine = forPlayer(player);
        Optional<Pending> found = id == null ? mine.stream().reduce((first, second) -> second)
                : mine.stream().filter(pending -> pending.id().equalsIgnoreCase(id)).findFirst();
        found.ifPresent(pending -> open.remove(pending.id()));
        return found;
    }

    public boolean asked(UUID from, UUID to) {
        return open.values().stream().anyMatch(pending -> pending.from().equals(from) && pending.to().equals(to));
    }
}
