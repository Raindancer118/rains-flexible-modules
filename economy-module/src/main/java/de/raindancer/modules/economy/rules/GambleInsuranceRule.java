package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;

/**
 * Bet insurance: a premium on every stake, a share of a lost stake back. A money sink whenever the premium is
 * more than the payback times the chance of losing — which the shipped numbers are for every game here.
 */
public final class GambleInsuranceRule implements IEconomyRule {

    /** {@code percent} of the stake, rounded up. */
    public Money premium(Money stake, int percent) {
        if (percent <= 0 || !stake.isPositive()) {
            return Money.ZERO;
        }
        return Money.of(Math.ceilDiv(Math.multiplyExact(stake.minor(), Math.min(percent, 1000)), 100));
    }

    /** {@code percent} of what was lost, rounded down, never more than the stake. */
    public Money payback(Money stake, Money payout, int percent) {
        if (percent <= 0 || !stake.isMoreThan(payout)) {
            return Money.ZERO;
        }
        Money lost = stake.minus(payout.max(Money.ZERO));
        return lost.share(Math.min(100, percent) / 100.0);
    }

    @Override
    public String describe() {
        return "what bet insurance costs and pays back";
    }
}
