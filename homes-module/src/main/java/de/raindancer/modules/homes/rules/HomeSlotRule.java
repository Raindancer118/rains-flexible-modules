package de.raindancer.modules.homes.rules;

import de.raindancer.core.social.economy.Money;

/** What an extra home slot costs and how many may be bought. Pure arithmetic: nothing is charged here. */
public final class HomeSlotRule implements IHomeRule {

    /** Whether buying slots is on at all: a price of zero is the switch. */
    public boolean isOn(Money base) {
        return base != null && base.isPositive();
    }

    /**
     * The written price of the next slot for somebody who has already bought this many.
     *
     * <p>Each step is the previous price plus {@code growthPercent} of it, rounded down per step so the
     * result is the same however it is asked. Saturates instead of overflowing.
     */
    public Money priceOfNext(Money base, int growthPercent, int alreadyBought) {
        if (base == null || !base.isPositive()) {
            return Money.ZERO;
        }
        long growth = Math.max(0, growthPercent);
        Money price = base;
        for (int step = 0; step < Math.max(0, alreadyBought) && growth > 0; step++) {
            // Whole numbers: a double would be a cent short on a percentage like 29.
            java.math.BigInteger raised = java.math.BigInteger.valueOf(price.minor())
                    .multiply(java.math.BigInteger.valueOf(100 + growth))
                    .divide(java.math.BigInteger.valueOf(100));
            if (raised.bitLength() > 62) {
                return Money.of(Long.MAX_VALUE);
            }
            price = Money.of(raised.longValueExact());
        }
        return price;
    }

    /** Whether one more may be bought; a ceiling of zero or less means no ceiling. */
    public boolean mayBuyAnother(int alreadyBought, int most) {
        return most <= 0 || alreadyBought < most;
    }

    @Override
    public String describe() {
        return "what an extra home slot costs";
    }
}
