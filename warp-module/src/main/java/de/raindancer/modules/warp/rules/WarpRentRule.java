package de.raindancer.modules.warp.rules;

import de.raindancer.core.social.economy.Money;

/** When rent is due, who owes it and when a warp counts as closed. Pure: nothing is charged or saved here. */
public final class WarpRentRule implements IWarpRule {

    public static final long WEEK_MILLIS = 7L * 24 * 3600 * 1000;

    /** Whether the paid time has been reached. */
    public boolean isDue(long paidUntil, long now) {
        return now >= paidUntil;
    }

    /**
     * The new paid-until after a week is bought.
     *
     * <p>From the old date when that is still close, so a week is never lost or gained by paying early or
     * late; from now when the warp is more than a week behind, so being away for months is not
     * back-rent.
     */
    public long extended(long paidUntil, long now) {
        long next = paidUntil + WEEK_MILLIS;
        return next > now ? next : now + WEEK_MILLIS;
    }

    /** Whether a new warp pays rent: rent is on, it has an owner, and the owner is not exempt. */
    public boolean enrols(Money rent, boolean hasOwner, boolean ownerBypasses) {
        return rent != null && rent.isPositive() && hasOwner && !ownerBypasses;
    }

    /** Closed only while rent is on, so switching rent off opens everything that was shut. */
    public boolean isClosed(boolean unpaid, Money rent) {
        return unpaid && rent != null && rent.isPositive();
    }

    @Override
    public String describe() {
        return "when rent is due and when a warp is closed for it";
    }
}
