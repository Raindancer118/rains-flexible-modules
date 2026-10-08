package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/** Who wins a draw, one chance per ticket, and what is left of the pot after the cut. */
public final class LotteryRule implements IEconomyRule {

    /**
     * @param ticket a number from 0 to the total tickets (exclusive); the holder of that ticket wins.
     *               Players are taken in a fixed order, so the same number always names the same winner
     */
    public Optional<UUID> winner(Map<UUID, Integer> tickets, long ticket) {
        long left = ticket;
        for (Map.Entry<UUID, Integer> each : new TreeMap<>(tickets).entrySet()) {
            left -= Math.max(0, each.getValue());
            if (left < 0) {
                return Optional.of(each.getKey());
            }
        }
        return Optional.empty();
    }

    public long total(Map<UUID, Integer> tickets) {
        return tickets.values().stream().mapToLong(count -> Math.max(0, count)).sum();
    }

    public Money prize(Money pot, double cut) {
        return pot.minus(pot.share(Math.max(0, Math.min(0.5, cut))));
    }

    /** How many of the wanted tickets fit under the per-player cap. */
    public int allowed(int already, int wanted, int most) {
        return Math.max(0, Math.min(wanted, most - already));
    }

    @Override
    public String describe() {
        return "who wins a lottery draw and what they win";
    }
}
