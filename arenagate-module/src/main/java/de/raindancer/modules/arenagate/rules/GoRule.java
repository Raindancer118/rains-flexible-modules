package de.raindancer.modules.arenagate.rules;

import java.util.Optional;

/** Whether somebody may set off for the arena now, and if not, which sentence says why. */
public final class GoRule implements IGateRule {

    /** @return the message key of the first reason, or empty when they may go */
    public Optional<String> refusal(boolean allowed, boolean alreadyTravelling) {
        if (!allowed) {
            return Optional.of("arenagate.not-allowed");
        }
        if (alreadyTravelling) {
            return Optional.of("arenagate.already-going");
        }
        return Optional.empty();
    }

    @Override
    public String describe() {
        return "who may set off for the arena";
    }
}
