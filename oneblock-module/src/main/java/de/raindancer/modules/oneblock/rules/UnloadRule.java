package de.raindancer.modules.oneblock.rules;

import de.raindancer.modules.oneblock.model.Hold;

import java.util.Optional;

/**
 * Whether an island's worlds should be up. An admin's hold beats everything; past that an island is up while
 * somebody is on it, or its block is working for an online team, and goes once it has sat empty for the grace —
 * unless its auto-unload was switched off, which keeps it up and brings it back after a restart.
 */
public final class UnloadRule implements IOneBlockRule {

    public enum Decision {
        /** Leave it as it is. */
        NOTHING,
        /** Loaded and staying. */
        STAY,
        /** Loaded, just found empty: start the clock. */
        START_COUNTING,
        UNLOAD,
        LOAD
    }

    /**
     * @param emptySince when it was first seen empty; negative while it is not known to be
     * @param working    its block is breaking itself for a team member online
     */
    public record Facts(boolean loaded, int players, long emptySince, boolean autoUnload, Optional<Hold> hold,
                        boolean working, long now, long graceMillis) {
    }

    public Decision decide(Facts facts) {
        Optional<Hold> hold = activeHold(facts.hold(), facts.now());
        if (hold.isPresent()) {
            boolean up = hold.get().kind() == Hold.Kind.UP;
            if (up) {
                return facts.loaded() ? Decision.STAY : Decision.LOAD;
            }
            return facts.loaded() ? Decision.UNLOAD : Decision.NOTHING;
        }
        if (!facts.autoUnload()) {
            return facts.loaded() ? Decision.STAY : Decision.LOAD;
        }
        if (!facts.loaded()) {
            return Decision.NOTHING;
        }
        if (facts.players() > 0 || facts.working()) {
            return Decision.STAY;
        }
        if (facts.emptySince() < 0) {
            return Decision.START_COUNTING;
        }
        return facts.now() - facts.emptySince() >= facts.graceMillis() ? Decision.UNLOAD : Decision.STAY;
    }

    public Optional<Hold> activeHold(Optional<Hold> hold, long now) {
        return hold.filter(held -> held.activeAt(now));
    }

    @Override
    public String describe() {
        return "whether an island's worlds should be loaded";
    }
}
