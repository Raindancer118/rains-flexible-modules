package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.BetRefusal;

import java.util.Optional;

/**
 * Fair odds, minus the house edge — for every game whose chance of winning is known exactly. A bet that
 * wins with chance p pays {@code stake × (1 − edge) / p}, so its expected return is exactly
 * {@code 1 − edge}, whatever p is.
 */
public final class GambleRule implements IEconomyRule {

    /** The longest odds a die roll may be bet at, and the shortest. */
    public static final double LEAST_CHANCE = 0.01;
    public static final double MOST_CHANCE = 0.95;

    /** No bet is too large: going all in is always allowed, at any game. */
    public Optional<BetRefusal> refusal(Money stake, Money least, Money lostToday, Money lossLimit) {
        if (least.isPositive() && !stake.isAtLeast(least)) {
            return Optional.of(BetRefusal.BELOW_MINIMUM);
        }
        if (lossLimit.isPositive() && lostToday.plus(stake).isMoreThan(lossLimit)) {
            return Optional.of(BetRefusal.LOSS_LIMIT);
        }
        return Optional.empty();
    }

    /** What a winning bet returns in total, stake included. Rounded down. */
    public Money payout(Money stake, double chance, double edge) {
        if (!(chance > 0) || chance > 1) {
            return Money.ZERO;
        }
        return stake.share((1.0 - Math.max(0, Math.min(0.5, edge))) / chance);
    }

    /** Rolling 1–100: over wins on more than the target, under on less. */
    public double diceChance(boolean over, int target) {
        int winning = over ? 100 - target : target - 1;
        return Math.max(0, Math.min(100, winning)) / 100.0;
    }

    public boolean diceValid(boolean over, int target) {
        double chance = diceChance(over, target);
        return target >= 1 && target <= 100 && chance >= LEAST_CHANCE && chance <= MOST_CHANCE;
    }

    public boolean diceWins(boolean over, int target, int roll) {
        return over ? roll > target : roll < target;
    }

    @Override
    public String describe() {
        return "whether a bet is taken, and what a win pays at fair odds less the house edge";
    }
}
