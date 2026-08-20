package de.raindancer.modules.xaeromap.model;

import java.util.UUID;

/**
 * One claim as the client's map knows it.
 *
 * <p>Open Parties and Claims has no notion of a claim with a name of its own: it keys everything on a
 * player and a "sub-config index", which is that player's <em>n</em>th set of claim settings, and the
 * name and colour hang off that pair. So each claim on this server is mapped to one index under its
 * primary owner — which is what gives every claim its own name and its own colour on the map instead
 * of one colour per player for all of theirs.
 *
 * <p>The {@code syncIndex} is the handle a region palette refers to a claim by, and has to be stable
 * for as long as a client holds it; see {@code SyncIndexTable}.
 *
 * @param colour packed {@code 0xRRGGBB}. Per <em>viewer</em>, not per claim — your own claims are
 *               drawn in your own colour, which is a decision that cannot live on the claim itself
 */
public record MapClaim(UUID claimId, UUID owner, int subIndex, int syncIndex, String name, int colour) {

    public MapClaim {
        if (claimId == null || owner == null || name == null) {
            throw new IllegalArgumentException("a claim on the map needs an id, an owner and a name");
        }
    }

    /** The same claim, drawn in another colour. */
    public MapClaim inColour(int newColour) {
        return new MapClaim(claimId, owner, subIndex, syncIndex, name, newColour);
    }
}
