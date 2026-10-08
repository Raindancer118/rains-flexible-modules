package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.EconomyResult.Outcome;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.BalanceChange;

/** Whether a balance may change by this much, and what it would be afterwards. The one sum that must be right. */
public final class BalanceRule implements IEconomyRule {

    /**
     * @param delta positive to add, negative to take
     * @param most  the most an account may hold; only enforced on money arriving, so an account above a
     *              lowered maximum can still be spent from
     */
    public BalanceChange apply(Money balance, Money delta, Money most, boolean frozen) {
        if (delta.isZero()) {
            return new BalanceChange(Outcome.INVALID_AMOUNT, balance);
        }
        if (frozen) {
            return new BalanceChange(Outcome.FROZEN, balance);
        }
        Money after;
        try {
            after = balance.plus(delta);
        } catch (ArithmeticException overflow) {
            return new BalanceChange(delta.isPositive() ? Outcome.TOO_MUCH : Outcome.NOT_ENOUGH, balance);
        }
        if (after.isNegative()) {
            return new BalanceChange(Outcome.NOT_ENOUGH, balance);
        }
        if (delta.isPositive() && after.isMoreThan(most)) {
            return new BalanceChange(Outcome.TOO_MUCH, balance);
        }
        return new BalanceChange(Outcome.DONE, after);
    }

    @Override
    public String describe() {
        return "whether a balance may change by an amount: never below zero, never past the most, never frozen";
    }
}
