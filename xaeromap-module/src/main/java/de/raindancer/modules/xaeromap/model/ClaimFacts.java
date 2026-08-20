package de.raindancer.modules.xaeromap.model;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Everything this module needs to know about one claim, and deliberately nothing else.
 *
 * <p>The whole point of the record: it is what a claims plugin hands over, so nothing past the seam in
 * {@code de.raindancer.modules.xaeromap.claims} ever mentions a claims-module type. That is not
 * tidiness — those classes are only on the classpath when a claims plugin is actually installed, and a
 * field of that type anywhere in the rest of the module turns "no claims plugin here" into a
 * {@code NoClassDefFoundError} on startup.
 *
 * @param chunkCoverage how many of a chunk's 256 block columns this claim covers, per chunk key.
 *                      Counted rather than a plain set of chunks because a claim clipping the corner
 *                      of a chunk and a claim filling it are drawn the same way by a map that works in
 *                      whole chunks, and something has to decide which of two claims that share a
 *                      chunk gets it
 * @param createdAt      the tiebreak when two claims cover a chunk equally: the older one keeps it,
 *                      so a new neighbour cannot repaint an established claim
 */
public record ClaimFacts(UUID id, String name, UUID owner, String ownerName, Set<UUID> members,
                         UUID worldId, String dimensionKey, long createdAt,
                         Map<Long, Integer> chunkCoverage) {

    public ClaimFacts {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(owner, "owner");
        members = members == null ? Set.of() : Set.copyOf(members);
        chunkCoverage = chunkCoverage == null ? Map.of() : Map.copyOf(chunkCoverage);
    }

    /** Whether this person is an owner or a member — what "one of mine" means on the map. */
    public boolean belongsTo(UUID who) {
        return who != null && (who.equals(owner) || members.contains(who));
    }

    /**
     * What has to change for a client to need telling.
     *
     * <p>Everything that is drawn, and only that: the footprint, the name, the owner, the world — plus
     * the member list, which is drawn indirectly, because a claim shared with you is shown in the
     * colour of yours rather than a stranger's. The bank, the flags and the fence are not in here: a
     * claim whose entry fee changed looks identical on a map, and treating that as a change would mean
     * re-sending claims all day.
     */
    public String fingerprint() {
        int shared = 0;
        for (UUID member : members) {
            // Sum, not a list: the set has no order, and an order invented here would report a change
            // every time the same members came back in a different one.
            shared += member.hashCode();
        }
        return id + "|" + name + "|" + owner + "|" + dimensionKey + "|" + chunkCoverage.size()
                + "|" + chunkCoverage.hashCode() + "|" + shared;
    }
}
