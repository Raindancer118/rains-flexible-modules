package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;

/** A share of the balance, capped per payout, never taking an account past its maximum. */
public final class InterestRule implements IEconomyRule {

    /** @param cap zero for no cap */
    public Money interest(Money balance, double rate, Money cap, Money most) {
        if (!balance.isPositive()) {
            return Money.ZERO;
        }
        Money earned = balance.share(rate);
        if (cap.isPositive()) {
            earned = earned.min(cap);
        }
        Money room = most.isAtLeast(balance) ? most.minus(balance) : Money.ZERO;
        return earned.min(room);
    }

    @Override
    public String describe() {
        return "the interest one payout adds to a balance";
    }
}
