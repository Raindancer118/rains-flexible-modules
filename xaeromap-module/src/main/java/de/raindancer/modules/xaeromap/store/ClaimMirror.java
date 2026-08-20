package de.raindancer.modules.xaeromap.store;

import de.raindancer.modules.xaeromap.model.ClaimFacts;
import de.raindancer.modules.xaeromap.model.ClaimMapSnapshot;
import de.raindancer.modules.xaeromap.model.MapDiff;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What each client has actually been told, so the next refresh sends only the difference.
 *
 * <p>Per player rather than one shared record, because two players can be shown different claims — see
 * {@code ClaimVisibilityRule} — and one shared record would count a claim sent to one of them as sent
 * to the other.
 *
 * <h2>Recorded after the fact, never as part of the asking</h2>
 * {@link #diff} answers and records nothing; the sender records what it actually sent, chunk by chunk,
 * through {@link #applyClaims} and {@link #applyChunks}. That split is the whole reason a sync can be
 * spread over several refreshes without lying: a player who was sent half of a large paste, or who
 * disconnected halfway through one, is behind by exactly the half that did not go out rather than by
 * nothing at all.
 *
 * <p>A player with no record at all is one who has not been synced: {@link #diff} then answers with the
 * whole picture, which is what a first sync is.
 */
public final class ClaimMirror {

    private record Sent(Map<UUID, String> fingerprints, Map<String, Map<Long, UUID>> chunks) {

        static Sent empty() {
            return new Sent(new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
        }
    }

    private final Map<UUID, Sent> byPlayer = new ConcurrentHashMap<>();

    /** Whether this player has been sent anything at all. */
    public boolean knows(UUID player) {
        return byPlayer.containsKey(player);
    }

    public int playerCount() {
        return byPlayer.size();
    }

    /** How many chunks this player is currently holding from us. */
    public int chunksKnownBy(UUID player) {
        Sent sent = byPlayer.get(player);
        if (sent == null) {
            return 0;
        }
        int total = 0;
        for (Map<Long, UUID> perDimension : sent.chunks().values()) {
            total += perDimension.size();
        }
        return total;
    }

    /** Starts this player from nothing, which is what follows a reset packet. */
    public void startFresh(UUID player) {
        byPlayer.put(player, Sent.empty());
    }

    /** What this player is missing. Records nothing. */
    public MapDiff diff(UUID player, ClaimMapSnapshot snapshot, Set<UUID> visible) {
        Sent sent = byPlayer.get(player);
        Map<UUID, String> before = sent == null ? Map.of() : sent.fingerprints();

        Set<ClaimFacts> changed = new HashSet<>();
        for (UUID claimId : visible) {
            ClaimFacts claim = snapshot.claim(claimId);
            if (claim != null && !claim.fingerprint().equals(before.get(claimId))) {
                changed.add(claim);
            }
        }
        Set<UUID> gone = new HashSet<>(before.keySet());
        gone.removeAll(visible);

        Map<String, Map<Long, UUID>> chunkChanges = new LinkedHashMap<>();
        Set<String> dimensions = new HashSet<>(snapshot.dimensions());
        if (sent != null) {
            dimensions.addAll(sent.chunks().keySet());
        }
        for (String dimension : dimensions) {
            Map<Long, UUID> now = visibleChunks(snapshot, dimension, visible);
            Map<Long, UUID> then = sent == null ? Map.of()
                    : sent.chunks().getOrDefault(dimension, Map.of());
            Map<Long, UUID> perDimension = new HashMap<>();
            now.forEach((chunk, claimId) -> {
                if (!claimId.equals(then.get(chunk))) {
                    perDimension.put(chunk, claimId);
                }
            });
            then.forEach((chunk, claimId) -> {
                if (!now.containsKey(chunk)) {
                    // A null value is how "nobody's any more" travels — which is why this map is a
                    // HashMap and not a Map.of().
                    perDimension.put(chunk, null);
                }
            });
            if (!perDimension.isEmpty()) {
                chunkChanges.put(dimension, java.util.Collections.unmodifiableMap(perDimension));
            }
        }
        return new MapDiff(changed, gone, chunkChanges);
    }

    /** Records claims whose name, colour or owner this player has now been told. */
    public void applyClaims(UUID player, Collection<ClaimFacts> told, Collection<UUID> forgotten) {
        Sent sent = byPlayer.computeIfAbsent(player, who -> Sent.empty());
        if (told != null) {
            for (ClaimFacts claim : told) {
                sent.fingerprints().put(claim.id(), claim.fingerprint());
            }
        }
        if (forgotten != null) {
            forgotten.forEach(sent.fingerprints()::remove);
        }
    }

    /**
     * Records chunks this player has now been told about.
     *
     * @param changes chunk key to the claim that holds it, or {@code null} for one that is now free
     */
    public void applyChunks(UUID player, String dimension, Map<Long, UUID> changes) {
        if (changes == null || changes.isEmpty()) {
            return;
        }
        Sent sent = byPlayer.computeIfAbsent(player, who -> Sent.empty());
        Map<Long, UUID> perDimension = sent.chunks()
                .computeIfAbsent(dimension, key -> new ConcurrentHashMap<>());
        changes.forEach((chunk, claimId) -> {
            if (claimId == null) {
                perDimension.remove(chunk);
            } else {
                perDimension.put(chunk, claimId);
            }
        });
        if (perDimension.isEmpty()) {
            sent.chunks().remove(dimension);
        }
    }

    /** Forgets a player — on quit, and before a resync that starts from a reset. */
    public void forget(UUID player) {
        byPlayer.remove(player);
    }

    public void forgetEverybody() {
        byPlayer.clear();
    }

    private static Map<Long, UUID> visibleChunks(ClaimMapSnapshot snapshot, String dimension,
                                                 Set<UUID> visible) {
        Map<Long, UUID> mine = new HashMap<>();
        snapshot.chunksIn(dimension).forEach((chunk, claimId) -> {
            if (visible.contains(claimId)) {
                mine.put(chunk, claimId);
            }
        });
        return mine;
    }
}
