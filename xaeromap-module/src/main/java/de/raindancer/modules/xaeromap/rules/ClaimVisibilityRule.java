package de.raindancer.modules.xaeromap.rules;

import de.raindancer.modules.xaeromap.model.ClaimFacts;
import de.raindancer.modules.xaeromap.model.MapAudience;

import java.util.UUID;

/**
 * Whether this player is shown this claim.
 *
 * <p>One answer, not two: unlike a menu, a map has no way to grey something out. A claim a player may
 * not see is a claim that is never sent, which is also the only way to keep it secret — a claim sent
 * and then hidden by the client would still be sitting in the client's own memory.
 */
public final class ClaimVisibilityRule implements IXaeroMapRule {

    private final MapAudience audience;

    public ClaimVisibilityRule(MapAudience audience) {
        this.audience = audience == null ? MapAudience.EVERYBODY : audience;
    }

    public boolean maySee(UUID viewer, ClaimFacts claim) {
        if (claim == null) {
            return false;
        }
        return switch (audience) {
            case EVERYBODY -> true;
            case MINE_AND_SHARED -> claim.belongsTo(viewer);
        };
    }

    public MapAudience audience() {
        return audience;
    }

    @Override
    public String describe() {
        return audience == MapAudience.EVERYBODY
                ? "every claim is drawn on every player's map"
                : "a player is drawn their own claims and the ones they are trusted on";
    }
}
