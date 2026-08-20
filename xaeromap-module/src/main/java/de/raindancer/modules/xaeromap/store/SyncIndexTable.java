package de.raindancer.modules.xaeromap.store;

import de.raindancer.modules.xaeromap.model.ClaimFacts;
import de.raindancer.modules.xaeromap.model.MapClaim;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The handles the client refers to claims by, and the promise that they do not move.
 *
 * <p>A region's palette is a list of {@code int}s, and a claim's name and colour arrive keyed on an
 * owner and a "sub-config index". Both numbers have to mean the same claim for as long as any client
 * holds them: hand out an index that used to be somebody else's claim and every chunk drawn from the
 * old palette is suddenly labelled with the new claim's name. So indices are allocated once per claim
 * and never reused, even after the claim is deleted — an int has room for far more claims than a server
 * will ever have, and the cost of being wrong here is a map that lies.
 *
 * <p>Kept in memory only. A restart is not a problem: a client is sent a reset and a full sync on its
 * next join, which is the same path a first-time join takes.
 *
 * <p>Thread safe, because a claim can be created on any region thread while the refresh timer is
 * reading the table.
 */
public final class SyncIndexTable {

    private record Identity(UUID owner, int subIndex, int syncIndex) {
    }

    private final Map<UUID, Identity> byClaim = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> nextSubIndex = new ConcurrentHashMap<>();
    private final AtomicInteger nextSyncIndex = new AtomicInteger(1);

    /**
     * This claim as the map knows it, allocating its handles the first time it is seen.
     *
     * <p>A claim whose <em>owner</em> has changed gets a fresh identity rather than keeping its old
     * one: the pair the client keys names and colours on is (owner, sub-index), so a transferred claim
     * has to become a different pair or it would be drawn under its previous owner's name.
     */
    public MapClaim mapClaim(ClaimFacts claim, int colour) {
        Identity identity = byClaim.compute(claim.id(), (id, existing) ->
                existing != null && existing.owner().equals(claim.owner()) ? existing : allocate(claim.owner()));
        return new MapClaim(claim.id(), identity.owner(), identity.subIndex(), identity.syncIndex(),
                claim.name(), colour);
    }

    /** The sync index a claim already has, or {@code 0} for one never seen. */
    public int syncIndexOf(UUID claimId) {
        Identity identity = byClaim.get(claimId);
        return identity == null ? 0 : identity.syncIndex();
    }

    public int size() {
        return byClaim.size();
    }

    private Identity allocate(UUID owner) {
        int subIndex = nextSubIndex.computeIfAbsent(owner, who -> new AtomicInteger()).getAndIncrement();
        return new Identity(owner, subIndex, nextSyncIndex.getAndIncrement());
    }
}
