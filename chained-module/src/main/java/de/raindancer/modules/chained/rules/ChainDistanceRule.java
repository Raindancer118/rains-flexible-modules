package de.raindancer.modules.chained.rules;

import de.raindancer.core.world.geometry.Proximity;
import org.bukkit.Location;

import java.util.Objects;

/**
 * Whether a move would separate a chained pair further than they are already allowed to be.
 *
 * <h2>Flat distance, not the full 3D one — on purpose</h2>
 * {@link Proximity#flat} ignores height. A player who jumps, falls off a ledge or climbs a tower is
 * not "separating" from their partner the way somebody walking away is; enforcing the full 3D
 * distance would turn ordinary vertical movement — mining straight down, building up — into a wall
 * the moment the two are directly above and below each other at their exact horizontal limit. The
 * challenge this module is for is about staying near each other on the ground, not about staying at
 * the same altitude.
 *
 * <h2>The comparison, and why it is not simply {@code nextDistance > maxDistance}</h2>
 * A pair already forced apart — because an admin widened the limit downward mid-run, or because they
 * were separated before the chain existed — has to be able to walk back <em>toward</em> each other
 * even while still over the limit. So a move is refused only when it would make the distance to the
 * partner both (a) over the limit, and (b) larger than it already was. Walking closer while still
 * over the limit passes (b) fails, so it is allowed; an admin widening the limit later is read fresh
 * on every check, so a distance that used to be refused can become fine the moment the setting
 * changes, with no special case needed for it.
 */
public final class ChainDistanceRule implements IChainedRule {

    /**
     * @param from               where the moving player currently is
     * @param to                 where they are trying to move to
     * @param otherPartyLocation where their chained partner is right now
     * @param maxDistance        blocks; how far apart the pair may be
     * @return whether this move should be refused
     */
    public boolean wouldExceed(Location from, Location to, Location otherPartyLocation,
                               double maxDistance) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(otherPartyLocation, "otherPartyLocation");

        // nextFlat first, and returned on early — checked on every move of a chained pair, and the
        // pair is within range on the overwhelming majority of them, so the second Proximity.flat call
        // (and its sqrt) is worth skipping whenever the first already settles the answer.
        double nextFlat = Proximity.flat(to, otherPartyLocation);
        if (nextFlat <= maxDistance) {
            return false;
        }
        double currentFlat = Proximity.flat(from, otherPartyLocation);
        return nextFlat > currentFlat;
    }

    @Override
    public String describe() {
        return "whether moving would separate a chained pair further than they are allowed to be";
    }
}
