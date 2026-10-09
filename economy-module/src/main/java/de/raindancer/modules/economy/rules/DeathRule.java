package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;

/** What dying costs. */
public final class DeathRule implements IEconomyRule {

    /** {@code percent} of the balance, rounded down, at most {@code most} unless that is zero. */
    public Money loss(Money balance, double percent, Money most) {
        if (!(percent > 0) || !balance.isPositive()) {
            return Money.ZERO;
        }
        Money loss = balance.share(Math.min(100, percent) / 100.0);
        return most.isPositive() ? loss.min(most) : loss;
    }

    @Override
    public String describe() {
        return "what dying costs";
    }
}
