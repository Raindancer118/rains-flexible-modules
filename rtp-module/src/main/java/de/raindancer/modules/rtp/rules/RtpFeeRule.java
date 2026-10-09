package de.raindancer.modules.rtp.rules;

import de.raindancer.core.social.economy.Money;

/**
 * What to do with somebody who asks for a random teleport, once money is in the picture.
 *
 * <p>Only the question the cooldown raises: a wait that is over, bypassed or still running, and whether
 * the owner lets it be paid off. Taking the money is {@code RtpFees}' job; this decides and does nothing.
 */
public final class RtpFeeRule implements IRtpRule {

    /** What follows from asking while the wait may be running. */
    public enum AtCooldown {
        /** Nothing in the way. */
        GO,
        /** The wait is running and they have said they will pay to skip it. */
        GO_PAYING,
        /** The wait is running and skipping it can be bought — tell them the price. */
        OFFER,
        /** The wait is running and cannot be bought. */
        REFUSE
    }

    public AtCooldown atCooldown(boolean ready, boolean bypasses, Money skipPrice, boolean wantsToPay) {
        if (ready || bypasses) {
            return AtCooldown.GO;
        }
        if (skipPrice == null || !skipPrice.isPositive()) {
            return AtCooldown.REFUSE;
        }
        return wantsToPay ? AtCooldown.GO_PAYING : AtCooldown.OFFER;
    }

    @Override
    public String describe() {
        return "what asking for a random teleport costs while the wait is running";
    }
}
