package de.raindancer.modules.xaeromap.rules;

import java.time.Duration;
import java.time.Instant;

/**
 * Whether it is time to look for claims that have changed.
 *
 * <p>Asked once a second by a fixed short timer rather than scheduling the timer at the configured
 * interval directly, so an owner who changes the interval sees it take effect on the next tick instead
 * of after the next restart — the same shape {@code SnapshotDueRule} uses, for the same reason.
 */
public final class RefreshDueRule implements IXaeroMapRule {

    public boolean isDue(Instant lastRefresh, Instant now, Duration interval) {
        if (now == null || interval == null) {
            return false;
        }
        if (lastRefresh == null) {
            // Never refreshed: due immediately, so a server that has just started does not show empty
            // maps for the length of one interval.
            return true;
        }
        return !Duration.between(lastRefresh, now).minus(interval).isNegative();
    }

    @Override
    public String describe() {
        return "whether enough time has passed to look for claims that have changed";
    }
}
