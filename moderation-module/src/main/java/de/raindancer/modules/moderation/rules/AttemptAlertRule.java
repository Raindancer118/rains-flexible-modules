package de.raindancer.modules.moderation.rules;

import java.time.Duration;
import java.time.Instant;

/**
 * Whether staff should hear about a punished player's attempt now: the first one always, after that only once
 * the quiet time since the last alert about them has passed — a banned player reconnecting every few seconds is
 * one alert, not a wall of them.
 */
public final class AttemptAlertRule implements IModerationRule {

    /** @param lastAlerted when staff were last told about this player, null for never */
    public boolean due(Instant lastAlerted, Instant now, Duration quiet) {
        return lastAlerted == null || !now.isBefore(lastAlerted.plus(quiet));
    }
}
