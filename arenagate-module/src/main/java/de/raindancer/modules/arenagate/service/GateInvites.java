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

    /**
     * The invite meant, removed: the one named — or, with no id, the only one there is. With several and no id it is
     * none: taking "the latest" would let somebody slip a different invite in just before the other types accept.
     */
    public Optional<Pending> take(UUID player, String id) {
        List<Pending> mine = forPlayer(player);
        Optional<Pending> found = id == null ? (mine.size() == 1 ? Optional.of(mine.getFirst()) : Optional.empty())
                : mine.stream().filter(pending -> pending.id().equalsIgnoreCase(id)).findFirst();
        found.ifPresent(pending -> open.remove(pending.id()));
        return found;
    }

    private final Map<String, Outgoing> outgoing = new ConcurrentHashMap<>();

    private record Outgoing(UUID from, long expires) {
    }

    /** An invite somebody here made to somebody in the arena — what a summon from the arena has to match. */
    public void outgoing(String id, UUID from) {
        outgoing.put(id, new Outgoing(from, clock.getAsLong() + LIFETIME * 2));
    }

    /** Whether this player made this invite from here (and it is not used yet). Used up by asking. */
    public boolean madeBy(String id, UUID player) {
        Outgoing found = outgoing.get(id == null ? "" : id);
        if (found == null || !found.from().equals(player) || found.expires() < clock.getAsLong()) {
            return false;
        }
        outgoing.remove(id);
        return true;
    }

    public boolean asked(UUID from, UUID to) {
        return open.values().stream().anyMatch(pending -> pending.from().equals(from) && pending.to().equals(to));
    }
}
