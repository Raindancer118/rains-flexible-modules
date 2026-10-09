package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;

/** The same reward again, soon, in the same place, pays less each time. */
public final class DiminishingRule implements IEconomyRule {

    /** {@code base} less {@code percent} for each of the {@code before} times it was earned lately. Rounded down. */
    public Money payout(Money base, int before, int percent) {
        if (percent <= 0 || before <= 0 || !base.isPositive()) {
            return base;
        }
        double factor = Math.pow(1.0 - Math.min(100, percent) / 100.0, before);
        return Money.of((long) Math.floor(base.minor() * factor));
    }

    @Override
    public String describe() {
        return "how much less the same reward pays when earned again soon";
    }
}
