package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.LotteryTicket;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * A number lottery, like the ones on television: everybody picks {@code pick} numbers from 1 to
 * {@code range}, the same number of balls is drawn, and the pot is shared out by how many each ticket got
 * right. All right takes the jackpot pool, one short the second, two short the third; a pool nobody hits
 * stays in the pot for the next draw — so the jackpot grows until somebody wins it.
 */
public final class LotteryRule implements IEconomyRule {

    /** What share of the pot each tier gets: all right, one short, two short. */
    public static final double[] SHARES = {0.60, 0.25, 0.15};

    public boolean valid(List<Integer> numbers, int pick, int range) {
        Set<Integer> distinct = new HashSet<>(numbers);
        return numbers.size() == pick && distinct.size() == pick
                && numbers.stream().allMatch(number -> number >= 1 && number <= range);
    }

    /** {@code pick} different numbers from 1 to {@code range}, sorted. */
    public List<Integer> draw(Random random, int pick, int range) {
        List<Integer> balls = new ArrayList<>();
        for (int number = 1; number <= range; number++) {
            balls.add(number);
        }
        java.util.Collections.shuffle(balls, random);
        return balls.subList(0, Math.min(pick, range)).stream().sorted().toList();
    }

    public int matches(List<Integer> ticket, List<Integer> drawn) {
        Set<Integer> balls = new HashSet<>(drawn);
        return (int) ticket.stream().filter(balls::contains).count();
    }

    /** 0 for all right, 1 for one short, 2 for two short; -1 for no prize. */
    public int tier(int matches, int pick) {
        int short_ = pick - matches;
        return short_ >= 0 && short_ < SHARES.length && matches > 0 ? short_ : -1;
    }

    /**
     * What every ticket wins: each tier's pool split evenly between its tickets. A tier without winners
     * keeps its pool in the pot.
     */
    public Map<UUID, Money> prizes(List<LotteryTicket> tickets, List<Integer> drawn, Money pot, int pick) {
        List<List<UUID>> winners = new ArrayList<>();
        for (int tier = 0; tier < SHARES.length; tier++) {
            winners.add(new ArrayList<>());
        }
        for (LotteryTicket ticket : tickets) {
            int tier = tier(matches(ticket.numbers(), drawn), pick);
            if (tier >= 0) {
                winners.get(tier).add(ticket.player());
            }
        }
        Map<UUID, Money> prizes = new HashMap<>();
        for (int tier = 0; tier < SHARES.length; tier++) {
            List<UUID> those = winners.get(tier);
            if (those.isEmpty()) {
                continue;
            }
            Money each = Money.of(pot.share(SHARES[tier]).minor() / those.size());
            for (UUID winner : those) {
                prizes.merge(winner, each, Money::plus);
            }
        }
        return prizes;
    }

    /** The chance one ticket gets exactly {@code matches} right — for showing the odds. */
    public double chance(int matches, int pick, int range) {
        return combinations(pick, matches) * combinations(range - pick, pick - matches) / combinations(range, pick);
    }

    private static double combinations(int n, int k) {
        if (k < 0 || k > n) {
            return 0;
        }
        double result = 1;
        for (int i = 1; i <= k; i++) {
            result = result * (n - k + i) / i;
        }
        return result;
    }

    /** How many of the wanted tickets fit under the per-player cap. */
    /** How many of {@code wanted} may still be bought; a limit of zero is no limit. */
    public int allowed(int already, int wanted, int most) {
        return most <= 0 ? Math.max(0, wanted) : Math.max(0, Math.min(wanted, most - already));
    }

    public Money afterCut(Money price, double cut) {
        return price.minus(price.share(Math.max(0, Math.min(0.5, cut))));
    }

    @Override
    public String describe() {
        return "a number lottery: valid picks, the draw, and how the pot is shared";
    }
}
