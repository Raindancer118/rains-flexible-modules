package de.raindancer.modules.invsnap.rules;

import java.time.Duration;
import java.time.Instant;

/**
 * Whether it is time for another automatic snapshot.
 *
 * <p>Asked once a second rather than scheduled at the configured interval directly, so a changed
 * interval takes effect on its own next tick instead of only after a restart — the timer that calls
 * this runs on a fixed short period and this decides whether that tick actually does anything.
 */
public final class SnapshotDueRule implements IInvSnapRule {

    public boolean isDue(Instant lastSnapshot, Instant now, Duration interval) {
        if (lastSnapshot == null || now == null || interval == null) {
            return false;
        }
        return !Duration.between(lastSnapshot, now).minus(interval).isNegative();
    }

    @Override
    public String describe() {
        return "whether enough time has passed since the last automatic snapshot";
    }
}
