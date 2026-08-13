package de.raindancer.modules.worldgate.rules;

import de.raindancer.modules.worldgate.model.GateState;

/**
 * Whether a player may cross a managed dimension's border right now.
 *
 * <h2>What is deliberately not decided here</h2>
 * Which dimension a portal actually leads to, and whether this event is even one this module owns —
 * both belong to the listener, which has the event to look at. What is left, and genuinely pure, is
 * the one three-way question: given a state, a direction and whether this player is staff, is the
 * crossing allowed.
 */
public final class GateRule implements IWorldGateRule {

    /**
     * @param state    the dimension's current state — {@code null} is treated as {@link GateState#OPEN},
     *                 since a dimension nobody has ever locked has no entry in the store at all
     * @param entering {@code true} for stepping into the managed dimension, {@code false} for leaving it
     * @param hasBypass whether this player holds {@code rainsworldgate.bypass}
     */
    public boolean allowed(GateState state, boolean entering, boolean hasBypass) {
        if (hasBypass) {
            return true;
        }
        // Leaving a locked dimension is never refused — DRAINED and CLOSED both mean "nobody new", not
        // "nobody out". A player already inside when the door was shut is not the door's problem.
        if (!entering) {
            return true;
        }
        GateState resolved = state == null ? GateState.OPEN : state;
        return resolved == GateState.OPEN;
    }

    @Override
    public String describe() {
        return "whether a player may enter or leave a locked dimension, before the portal event is "
                + "cancelled";
    }
}
