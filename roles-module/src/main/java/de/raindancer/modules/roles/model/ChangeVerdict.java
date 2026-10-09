package de.raindancer.modules.roles.model;

import java.time.Duration;

/**
 * Whether a player may take a role now.
 *
 * @param left how long until they may, when they may not yet
 */
public record ChangeVerdict(boolean allowed, Reason reason, Duration left) {

    public enum Reason {
        /** They have no role yet. */
        FIRST,
        /** The wait since their last choice is over. */
        WAITED,
        /** Staff skipping the wait. */
        BYPASS,
        /** It is the role they have. */
        SAME,
        /** Too soon since the last choice. */
        WAIT
    }

    public static ChangeVerdict yes(Reason reason) {
        return new ChangeVerdict(true, reason, Duration.ZERO);
    }

    public static ChangeVerdict no(Reason reason, Duration left) {
        return new ChangeVerdict(false, reason, left);
    }
}
