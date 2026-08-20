package de.raindancer.modules.xaeromap.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every claim on the server, already reduced to what a map draws: which claim owns which chunk.
 *
 * <p>Built once per refresh and shared by every player being synced, which is the point — working the
 * chunk coverage out per player would repeat the same polygon arithmetic once for each of them, and it
 * is the same answer every time. What differs per player is only which of these claims they may see and
 * what colour theirs are, and both of those are a filter over this rather than a second pass over the
 * claims.
 *
 * <p>Immutable, and safe to hand to another thread.
 */
public record ClaimMapSnapshot(Map<UUID, ClaimFacts> claims, Map<String, Map<Long, UUID>> byDimension) {

    public static final ClaimMapSnapshot EMPTY = new ClaimMapSnapshot(Map.of(), Map.of());

    public ClaimMapSnapshot {
        claims = claims == null ? Map.of() : Map.copyOf(claims);
        Map<String, Map<Long, UUID>> copied = new LinkedHashMap<>();
        if (byDimension != null) {
            byDimension.forEach((dimension, chunks) -> copied.put(dimension, Map.copyOf(chunks)));
        }
        byDimension = Map.copyOf(copied);
    }

    public boolean isEmpty() {
        return claims.isEmpty();
    }

    /** How many chunks are shown as claimed in total — the figure the diagnostic reports. */
    public int chunkCount() {
        int total = 0;
        for (Map<Long, UUID> chunks : byDimension.values()) {
            total += chunks.size();
        }
        return total;
    }

    public List<String> dimensions() {
        return List.copyOf(byDimension.keySet());
    }

    public Map<Long, UUID> chunksIn(String dimensionKey) {
        return byDimension.getOrDefault(dimensionKey, Map.of());
    }

    public ClaimFacts claim(UUID claimId) {
        return claims.get(claimId);
    }
}
