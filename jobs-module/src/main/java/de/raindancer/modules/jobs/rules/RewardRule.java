package de.raindancer.modules.jobs.rules;

import de.raindancer.core.social.economy.Money;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a goal pays and to whom. The reward is the median balance of the server's players — a lot to somebody
 * poor, little to somebody rich, and not moved by the one player with a fortune. A goal reached pays all of it;
 * a goal missed pays for the part that was reached. Each contributor gets their share of what they gave.
 */
public final class RewardRule implements IJobsRule {

    public Money median(List<Money> balances) {
        if (balances.isEmpty()) {
            return Money.ZERO;
        }
        List<Money> sorted = new ArrayList<>(balances);
        sorted.sort(Comparator.naturalOrder());
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(middle);
        }
        BigInteger sum = BigInteger.valueOf(sorted.get(middle - 1).minor()).add(BigInteger.valueOf(sorted.get(middle).minor()));
        return Money.of(sum.divide(BigInteger.TWO).longValueExact());
    }

    /**
     * @param percent the owner's percent of the median
     * @param least   the smallest reward, whatever the median
     * @param most    the largest, or zero for no limit
     */
    public Money pool(Money median, int percent, Money least, Money most) {
        Money scaled = median.share(Math.max(0, percent) / 100.0);
        Money floor = scaled.max(least);
        return most.isPositive() ? floor.min(most) : floor;
    }

    /** A reward moved by the owner's pay scale, rounded down; 100 leaves it as it is. */
    public Money scaled(Money reward, int percent) {
        if (percent == 100) {
            return reward;
        }
        BigInteger scaled = BigInteger.valueOf(reward.minor()).multiply(BigInteger.valueOf(Math.max(0, percent)))
                .divide(BigInteger.valueOf(100));
        return Money.of(scaled.longValueExact());
    }

    /** Who is paid what for a goal that asked for {@code amount}. Rounded down; nobody is paid for nothing. */
    public Map<UUID, Money> payouts(Map<UUID, Integer> given, int amount, Money pool) {
        long total = given.values().stream().mapToLong(each -> Math.max(0, each)).sum();
        Map<UUID, Money> paid = new LinkedHashMap<>();
        if (total <= 0 || amount <= 0 || !pool.isPositive()) {
            return paid;
        }
        BigInteger reached = BigInteger.valueOf(Math.min(total, amount));
        for (Map.Entry<UUID, Integer> each : given.entrySet()) {
            if (each.getValue() <= 0) {
                continue;
            }
            // pool × (reached / amount) × (given / total), in one division so nothing is rounded twice
            BigInteger share = BigInteger.valueOf(pool.minor()).multiply(reached).multiply(BigInteger.valueOf(each.getValue()))
                    .divide(BigInteger.valueOf(amount).multiply(BigInteger.valueOf(total)));
            if (share.signum() > 0) {
                paid.put(each.getKey(), Money.of(share.longValueExact()));
            }
        }
        return paid;
    }

    @Override
    public String describe() {
        return "what a goal pays: the median balance, shared by what each gave, in part for a goal missed";
    }
}
