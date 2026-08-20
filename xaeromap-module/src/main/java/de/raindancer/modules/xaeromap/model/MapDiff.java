package de.raindancer.modules.xaeromap.model;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What one client is behind by, and nothing more.
 *
 * <p>The alternative to a diff is re-sending every region on every refresh, which on a server with a
 * few thousand claims is a steady stream of kilobytes per player for a map that mostly has not changed.
 * A claim being made is a handful of chunks; this is what makes that cost a handful of chunks.
 *
 * @param changed  claims whose name, colour or owner the client needs (re)telling — a new claim, a
 *                 renamed one, a transferred one
 * @param gone     claims the client should forget. Their chunks are in {@code chunks} as well, with a
 *                 {@code null} owner: the mod needs to be told the chunk is free, not only that the
 *                 claim is gone
 * @param chunks   per dimension key, the chunks that changed hands. A {@code null} value is "nobody's"
 */
public record MapDiff(Set<ClaimFacts> changed, Set<UUID> gone, Map<String, Map<Long, UUID>> chunks) {

    public MapDiff {
        changed = changed == null ? Set.of() : Set.copyOf(changed);
        gone = gone == null ? Set.of() : Set.copyOf(gone);
        chunks = chunks == null ? Map.of() : Map.copyOf(chunks);
    }

    public boolean isEmpty() {
        return changed.isEmpty() && gone.isEmpty() && chunkChanges() == 0;
    }

    public int chunkChanges() {
        int total = 0;
        for (Map<Long, UUID> perDimension : chunks.values()) {
            total += perDimension.size();
        }
        return total;
    }
}
