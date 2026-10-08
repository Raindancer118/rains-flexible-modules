package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.DailyClaim;

/** Once a day, with a streak for days in a row. Days are epoch days in the server's own time zone. */
public final class DailyRule implements IEconomyRule {

    /** @param lastDay the day last claimed, or -1 for never */
    public DailyClaim claim(long lastDay, int streak, long today, int mostStreak) {
        if (lastDay >= today) {
            return new DailyClaim(false, streak, lastDay, 1);
        }
        int next = lastDay == today - 1 ? streak + 1 : 1;
        return new DailyClaim(true, Math.max(1, Math.min(Math.max(1, mostStreak), next)), today, 0);
    }

    public Money amount(Money base, Money bonusPerDay, int streak) {
        try {
            return base.plus(bonusPerDay.times(Math.max(0, streak - 1)));
        } catch (ArithmeticException overflow) {
            return base;
        }
    }

    @Override
    public String describe() {
        return "whether /daily pays today, and how long the streak is";
    }
}
