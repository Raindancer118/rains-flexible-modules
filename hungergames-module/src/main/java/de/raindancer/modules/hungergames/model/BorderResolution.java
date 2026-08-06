package de.raindancer.modules.hungergames.model;

import java.time.Duration;

/**
 * One concrete way to resolve a {@link BorderConflict}, carrying the effect it would have so a screen can
 * show it in a button's lore before anybody confirms it.
 *
 * <p>Nothing here changes anything by existing — see {@link BorderMath#apply}, which is the only thing
 * that turns a chosen option into a new {@link BorderSettings}, and only after a click.
 */
public sealed interface BorderResolution {

    /** Lengthen the phase's duration until the speed fits. */
    record AdjustDuration(Duration newDuration, double resultingSpeed) implements BorderResolution {
    }

    /** Raise the target size until the speed, or the minimum, is respected. */
    record AdjustTarget(double newTarget, double resultingSpeed) implements BorderResolution {
    }

    /** Move the phase's start time earlier so it fits inside the game length. */
    record ShiftStart(Duration newStart) implements BorderResolution {
    }

    /** Lengthen the round itself so the phase fits. */
    record AdjustGameTime(Duration newGameDuration) implements BorderResolution {
    }

    /**
     * Treat the configured speed as a ceiling rather than a target (mode {@link BorderPhaseConfig.Mode#MAX_SPEED}):
     * shrink at the fairness limit and let the duration stretch to match.
     */
    record UseSpeedAsMax(Duration effectiveDuration, double effectiveSpeed) implements BorderResolution {
    }

    /** Discard the change — the configuration as it was stands. */
    record Discard() implements BorderResolution {
    }
}
