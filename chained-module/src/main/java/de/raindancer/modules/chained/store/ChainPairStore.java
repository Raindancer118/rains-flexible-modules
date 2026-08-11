package de.raindancer.modules.chained.store;

import de.raindancer.modules.chained.model.ChainPair;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every chain pair a server currently has — module-owned, in memory, and gone on restart, unlike
 * warps or claims: a pair is a run's setup, made fresh by an admin each session rather than a thing
 * worth persisting across a restart nobody expects it to survive.
 *
 * <h2>Keyed by either player</h2>
 * Both sides of a pair map to the same {@link ChainPair}, so a lookup from either player is one map
 * read rather than a scan — the same shape warps and teams use for "which of these am I in".
 * {@link #pair} and {@link #unpair} keep both entries in step: a pair is written or removed as one
 * atomic step, under this store's own monitor, so a reader between the two writes never sees one
 * side paired and the other not.
 *
 * <h2>Multiple concurrent pairs</h2>
 * Nothing here assumes there is only one. A server running three simultaneous chained duos has three
 * independent entries, and pairing a player who is already in one silently replaces it — the same
 * "last one wins" a re-pair implies.
 */
public final class ChainPairStore {

    private final Map<UUID, ChainPair> byPlayer = new ConcurrentHashMap<>();

    /**
     * Registers a pair, replacing whatever pair either player was already in.
     *
     * @return the pair, for chaining
     */
    public synchronized ChainPair pair(ChainPair newPair) {
        Objects.requireNonNull(newPair, "newPair");
        dissolve(newPair.a());
        dissolve(newPair.b());
        byPlayer.put(newPair.a(), newPair);
        byPlayer.put(newPair.b(), newPair);
        return newPair;
    }

    /** Dissolves whichever pair this player is in, if any. */
    public synchronized boolean unpair(UUID player) {
        return player != null && dissolve(player);
    }

    private boolean dissolve(UUID player) {
        ChainPair existing = byPlayer.remove(player);
        if (existing == null) {
            return false;
        }
        byPlayer.remove(existing.otherOf(player));
        return true;
    }

    public Optional<ChainPair> pairOf(UUID player) {
        return player == null ? Optional.empty() : Optional.ofNullable(byPlayer.get(player));
    }

    /** Every pair this server has right now, each appearing once. */
    public Set<ChainPair> all() {
        return new LinkedHashSet<>(byPlayer.values());
    }

    public int count() {
        return all().size();
    }
}
