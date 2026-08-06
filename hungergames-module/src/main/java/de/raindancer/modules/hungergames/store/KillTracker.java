package de.raindancer.modules.hungergames.store;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Counts kills per tribute within one round.
 *
 * <p>Nothing more sophisticated than a map — kills matter for the leaderboard and for sponsor rules that
 * key off a kill count, and neither needs anything this does not already give them. Lives in {@code store}
 * rather than {@code model} because it is exactly what its name says: something that holds a number and
 * lets it be added to, restored and reset, not a value that answers questions about itself.
 */
public final class KillTracker {

    private final Map<UUID, Integer> kills = new LinkedHashMap<>();

    /**
     * Adds one kill.
     *
     * @return the killer's new total
     */
    public int increment(UUID killer) {
        return kills.merge(killer, 1, Integer::sum);
    }

    public int kills(UUID participant) {
        return kills.getOrDefault(participant, 0);
    }

    /** An immutable snapshot of every count. */
    public Map<UUID, Integer> snapshot() {
        return Map.copyOf(kills);
    }

    /** Tributes ordered by kills, descending, at most {@code limit} entries. */
    public List<Map.Entry<UUID, Integer>> top(int limit) {
        return kills.entrySet().stream()
                .sorted(Comparator.<Map.Entry<UUID, Integer>>comparingInt(Map.Entry::getValue).reversed())
                .limit(limit)
                .toList();
    }

    /** Restores saved counts (session restore). */
    public void restore(Map<UUID, Integer> saved) {
        kills.clear();
        kills.putAll(saved);
    }

    public void reset() {
        kills.clear();
    }
}
