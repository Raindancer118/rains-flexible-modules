package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;

/** What the wealth tax takes from a balance, and when it is due. */
public final class WealthTaxRule implements IEconomyRule {

    /** That percentage of whatever is above the allowance, rounded down; never more than the balance. */
    public Money owed(Money balance, double percent, Money allowance) {
        if (!(percent > 0)) {
            return Money.ZERO;
        }
        long above = balance.minor() - Math.max(0, allowance.minor());
        if (above <= 0) {
            return Money.ZERO;
        }
        long owed = (long) Math.floor(above * Math.min(100, percent) / 100.0);
        return Money.of(Math.min(owed, balance.minor()));
    }

    /** Never run counts as not due: switching the tax on starts its clock rather than taxing at once. */
    public boolean due(long lastRun, long now, int everyHours) {
        return lastRun > 0 && now - lastRun >= Math.max(1, everyHours) * 3_600_000L;
    }

    @Override
    public String describe() {
        return "what the wealth tax takes from a balance, and when";
    }
}
