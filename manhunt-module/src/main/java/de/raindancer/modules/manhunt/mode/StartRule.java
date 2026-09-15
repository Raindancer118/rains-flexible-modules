package de.raindancer.modules.manhunt.mode;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Whether a hunt could be played with what the lobby has right now — and if not, which sentence the
 * player who pressed the block is shown.
 *
 * <h2>Why a pure function rather than a check inside the mode</h2>
 * These three refusals are the whole difference between "press start and see what happens" and a
 * lobby that says what is missing. That makes them worth testing exactly, and none of them needs a
 * server to decide: they are questions about a goal being set and two sets of ids.
 */
public final class StartRule {

    /** Nobody on the Runner side — a hunt with nothing to hunt. */
    public static final String NO_RUNNER = "manhunt.start.no-runner";

    /** Everybody present is a Runner — nobody would be chasing. */
    public static final String NO_HUNTER = "manhunt.start.no-hunter";

    /** No advancement goal, so the Runners could never win and only being caught could end it. */
    public static final String NO_GOAL = "manhunt.start.no-goal";

    private StartRule() {
    }

    /**
     * @param hasGoal      whether the lobby has an advancement goal set — the Runners' way to win
     * @param participants everybody the start block swept up
     * @param runners      who is on the Runner side, whether or not they are in {@code participants}
     * @return the wording key for why this hunt may not start, or empty when it may
     */
    public static Optional<String> refuse(boolean hasGoal, Set<UUID> participants, Set<UUID> runners) {
        if (!hasGoal) {
            return Optional.of(NO_GOAL);
        }
        if (participants == null || participants.isEmpty()) {
            return Optional.of(NO_RUNNER);
        }
        boolean anyRunner = participants.stream().anyMatch(runners::contains);
        if (!anyRunner) {
            return Optional.of(NO_RUNNER);
        }
        boolean anyHunter = participants.stream().anyMatch(id -> !runners.contains(id));
        if (!anyHunter) {
            return Optional.of(NO_HUNTER);
        }
        return Optional.empty();
    }
}
