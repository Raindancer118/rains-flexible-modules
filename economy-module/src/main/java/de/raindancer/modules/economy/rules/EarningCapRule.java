package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;

/** How much of a reward still fits under the hourly cap. */
public final class EarningCapRule implements IEconomyRule {

    /** @param cap zero for no cap */
    public Money allowed(Money earnedThisHour, Money cap, Money reward) {
        if (!cap.isPositive()) {
            return reward;
        }
        if (earnedThisHour.isAtLeast(cap)) {
            return Money.ZERO;
        }
        return reward.min(cap.minus(earnedThisHour));
    }

    @Override
    public String describe() {
        return "how much of a reward fits under the hourly earning cap";
    }
}
