package de.raindancer.modules.arena.service;

import de.raindancer.modules.arena.api.MatchSpec;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Fights two (or more) players have agreed to — by an invite accepted here or on the survival server — waiting until
 * everybody in them is on this server. Then it goes to the queue ahead of everybody else; if somebody never comes, it
 * is dropped after two minutes and the others are told.
 */
public final class AgreedFights {

    /** @param sides who is on which side; a duel has two of one, a mob run one of several */
    public record Agreed(String id, MatchSpec spec, List<Set<UUID>> sides, Set<UUID> here, long expires) {

        public Set<UUID> members() {
            Set<UUID> all = new HashSet<>();
            sides.forEach(all::addAll);
            return all;
        }

        public boolean complete() {
            return here.containsAll(members());
        }
    }

    private static final long LIFETIME = 120_000;

    private final Map<String, Agreed> waiting = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public AgreedFights(LongSupplier clock) {
        this.clock = clock;
    }

    public void agree(String id, MatchSpec spec, List<Set<UUID>> sides) {
        waiting.putIfAbsent(id, new Agreed(id, spec, List.copyOf(sides), ConcurrentHashMap.newKeySet(),
                clock.getAsLong() + LIFETIME));
    }

    /** Somebody of an agreed fight is here. @return the fight, when that made it complete (and it is removed) */
    public synchronized Optional<Agreed> arrived(String id, UUID player) {
        Agreed agreed = waiting.get(id);
        if (agreed == null || !agreed.members().contains(player)) {
            return Optional.empty();
        }
        agreed.here().add(player);
        if (agreed.complete()) {
            waiting.remove(id);
            return Optional.of(agreed);
        }
        return Optional.empty();
    }

    public Optional<Agreed> get(String id) {
        return Optional.ofNullable(waiting.get(id));
    }

    /** @return the ones that ran out, so whoever is here can be told */
    public List<Agreed> sweep() {
        long now = clock.getAsLong();
        List<Agreed> expired = waiting.values().stream().filter(agreed -> agreed.expires() < now).toList();
        expired.forEach(agreed -> waiting.remove(agreed.id()));
        return expired;
    }

    public void forget(UUID player) {
        waiting.values().removeIf(agreed -> agreed.members().contains(player) && agreed.here().contains(player));
    }
}
