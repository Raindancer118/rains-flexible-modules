package de.raindancer.modules.tpa.model;

import java.util.UUID;

/**
 * One person having asked another whether they may come, or whether they will.
 *
 * <p>Plain values and no server: whether a request has run out, who travels and how long is left are
 * all arithmetic, and keeping them arithmetic is what makes the whole model testable. What it does not
 * hold is a destination — that is read at the moment of arrival, because the person being travelled to
 * may have walked off while the countdown ran.
 *
 * @param madeAt    when it was asked, in milliseconds
 * @param expiresAt when it stops standing
 */
public record TpaRequest(UUID from, UUID to, TpaKind kind, long madeAt, long expiresAt) {

    /**
     * Whether it has run out.
     *
     * <p>Asked rather than swept: a request is expired the moment somebody looks at it, whatever a
     * timer has or has not done. The old plugin scheduled its sweep at exactly the expiry and the task
     * landed milliseconds early — the sweep found nothing, the one-shot task was spent, and the request
     * sat there for ever with nobody told. Asking cannot have that bug.
     */
    public boolean isExpired(long now) {
        return now >= expiresAt;
    }

    /** How many seconds it still stands for, never negative. */
    public long secondsLeft(long now) {
        return Math.max(0, (expiresAt - now) / 1000L);
    }

    /** Who actually moves when this is accepted. */
    public UUID traveller() {
        return kind.travellerOf(from, to);
    }

    /** Who they end up standing next to. */
    public UUID destination() {
        return kind.destinationOf(from, to);
    }

    /** Whether this is the request that person asked of this one. */
    public boolean isBetween(UUID asker, UUID asked) {
        return from.equals(asker) && to.equals(asked);
    }
}
