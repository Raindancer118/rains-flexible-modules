package de.raindancer.modules.xaeromap.rules;

import de.raindancer.modules.xaeromap.model.ClaimFacts;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Which claim, if any, a chunk is drawn as belonging to.
 *
 * <h2>Why a chunk has to be decided at all</h2>
 * A claim here is an arbitrary polygon of block columns with a top and a bottom. Xaero's map draws
 * whole chunks and nothing smaller, because that is the granularity the protocol underneath it has. So
 * every chunk a claim reaches into is either shown as that claim's or not shown at all, and two things
 * follow that a plain "does it overlap" answer gets wrong:
 *
 * <ul>
 *   <li><b>Two claims can share a chunk.</b> Claims may sit side by side across a chunk boundary, and
 *       one of them has to win. The one covering more of the chunk does; equal, the older one keeps it,
 *       so a new neighbour cannot repaint an established claim by being drawn second.</li>
 *   <li><b>A claim can barely touch a chunk.</b> A claim clipping one column of a chunk would otherwise
 *       paint all 256 of them, which reads on the map as a claim wider than it is. The threshold is the
 *       server's to set; at its default of one percent nothing visible is missing, which is the right
 *       default for a map somebody navigates by.</li>
 * </ul>
 */
public final class ChunkCoverageRule implements IXaeroMapRule {

    /** Block columns in a chunk. */
    public static final int COLUMNS_PER_CHUNK = 256;

    private final int minimumPercent;

    public ChunkCoverageRule(int minimumPercent) {
        this.minimumPercent = Math.max(1, Math.min(100, minimumPercent));
    }

    /**
     * The winner for every chunk any of these claims reaches.
     *
     * @param claims claims in <em>one</em> world. Mixing worlds would let a claim in the nether win a
     *               chunk in the overworld, since a chunk key says nothing about which world it is in
     */
    public Map<Long, UUID> chunksOf(Collection<ClaimFacts> claims) {
        Map<Long, UUID> owner = new HashMap<>();
        Map<Long, ClaimFacts> winner = new HashMap<>();
        Map<Long, Integer> best = new HashMap<>();
        for (ClaimFacts claim : claims) {
            for (Map.Entry<Long, Integer> covered : claim.chunkCoverage().entrySet()) {
                int columns = covered.getValue() == null ? 0 : covered.getValue();
                if (!enough(columns)) {
                    continue;
                }
                long chunk = covered.getKey();
                Integer standing = best.get(chunk);
                if (standing == null || beats(columns, claim, standing, winner.get(chunk))) {
                    best.put(chunk, columns);
                    winner.put(chunk, claim);
                    owner.put(chunk, claim.id());
                }
            }
        }
        return owner;
    }

    /** Whether this much of a chunk is enough to show it as claimed. */
    public boolean enough(int coveredColumns) {
        return coveredColumns > 0
                && coveredColumns * 100L >= (long) minimumPercent * COLUMNS_PER_CHUNK;
    }

    public int minimumPercent() {
        return minimumPercent;
    }

    private static boolean beats(int columns, ClaimFacts claim, int standingColumns,
                                 ClaimFacts standing) {
        if (columns != standingColumns) {
            return columns > standingColumns;
        }
        if (standing == null) {
            return true;
        }
        if (claim.createdAt() != standing.createdAt()) {
            return claim.createdAt() < standing.createdAt();
        }
        // Two claims created in the same millisecond covering a chunk equally: settled by id, purely so
        // that two syncs of the same unchanged server never disagree with each other.
        return claim.id().compareTo(standing.id()) < 0;
    }

    @Override
    public String describe() {
        return "which claim a chunk belongs to on the map, at " + minimumPercent
                + "% of the chunk or more";
    }
}
