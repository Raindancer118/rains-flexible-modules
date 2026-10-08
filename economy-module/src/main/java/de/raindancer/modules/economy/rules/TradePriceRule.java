package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;

import java.util.Optional;

/** What one item costs to buy and pays to sell. Buying rounds up, selling rounds down. */
public final class TradePriceRule implements IEconomyRule {

    public Money unitBuy(Money value, double multiplier, double markup) {
        double exact = value.minor() * multiplier * markup;
        if (!(exact > 0)) {
            return Money.of(1);
        }
        return Money.of(Math.max(1, (long) Math.min(Long.MAX_VALUE / 2, Math.ceil(exact - 1e-9))));
    }

    public Money unitSell(Money value, double multiplier, double ratio) {
        double exact = value.minor() * multiplier * ratio;
        if (!(exact > 0)) {
            return Money.ZERO;
        }
        return Money.of((long) Math.min(Long.MAX_VALUE / 2, Math.floor(exact + 1e-9)));
    }

    /** A sell price at or above the buy price would be a money machine; it is kept a cent below. */
    public Money capSellBelowBuy(Money sell, Money buy) {
        if (sell.isAtLeast(buy)) {
            return buy.minor() > 1 ? Money.of(buy.minor() - 1) : Money.ZERO;
        }
        return sell;
    }

    public Optional<Money> total(Money unit, int quantity) {
        try {
            return Optional.of(unit.times(Math.max(0, quantity)));
        } catch (ArithmeticException overflow) {
            return Optional.empty();
        }
    }

    @Override
    public String describe() {
        return "what one item costs to buy and pays to sell, rounded against the player by a cent at most";
    }
}
