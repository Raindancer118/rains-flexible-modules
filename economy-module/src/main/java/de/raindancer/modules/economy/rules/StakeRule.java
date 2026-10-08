package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;

/** How big the offered bets are: shares of what the player has, rounded, within the server's bet limits. */
public final class StakeRule implements IEconomyRule {

    /** Rounded down to 1, 2 or 5 times a power of ten. */
    public Money nice(Money amount) {
        long value = amount.minor();
        if (value <= 0) {
            return Money.ZERO;
        }
        long power = 1;
        while (power <= value / 10) {
            power *= 10;
        }
        long lead = value / power;
        return Money.of((lead >= 5 ? 5 : lead >= 2 ? 2 : 1) * power);
    }

    /**
     * That share of the balance, rounded unless it is all of it.
     *
     * @param most the largest bet; zero for none
     */
    public Money share(Money balance, double fraction, Money least, Money most) {
        Money part = fraction >= 1 ? balance : nice(balance.share(fraction));
        return clamp(part, least, most);
    }

    /** What a game starts with: about a hundredth of the balance. */
    public Money opening(Money balance, Money least, Money most) {
        return clamp(nice(balance.share(0.01)), least, most);
    }

    public Money clamp(Money amount, Money least, Money most) {
        Money floor = least.isPositive() ? least : Money.of(1);
        Money at = amount.max(floor);
        return most.isPositive() ? at.min(most) : at;
    }

    @Override
    public String describe() {
        return "how big the offered bets are, from what a player has";
    }
}
