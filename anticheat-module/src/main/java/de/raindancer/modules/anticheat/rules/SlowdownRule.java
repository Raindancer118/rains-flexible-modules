package de.raindancer.modules.anticheat.rules;

import de.raindancer.modules.anticheat.model.SlowState;

/**
 * When sneaking and using an item slow a client down. The slowdown follows the client's pose and its
 * use state, which change a tick after the key or the click, and the server learns of them when it
 * reads the packet — not when the move made at that moment is judged. Holding the first moves of a
 * sneak or a use to the slowed speed convicts honest players who tap shift along an edge.
 */
public final class SlowdownRule implements IAntiCheatRule {

    /**
     * @param state     the sneak key, move by move
     * @param attribute the sneaking speed attribute (0.3, higher with Swift Sneak)
     * @return the factor on the input: 1 when not slowed (key up, or still in the press's grace)
     */
    public double sneak(SlowState state, double attribute) {
        if (!state.on() || state.graced()) {
            return 1.0;
        }
        return Math.max(0, Math.min(1, attribute));
    }

    /** @param multiplier what the item does to the input (0.2 for most food and shields) */
    public double use(SlowState state, double multiplier) {
        if (!state.on() || state.graced()) {
            return 1.0;
        }
        return Math.max(0, Math.min(1, multiplier));
    }

    @Override
    public String describe() {
        return "when sneaking and using an item start to slow a client down";
    }
}
