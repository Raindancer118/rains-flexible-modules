package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;

import java.util.Map;
import java.util.UUID;

/** Who a raffle's draw picks, how many tickets anybody may still buy, and the house's share. */
public final class RaffleRule implements IEconomyRule {

    /**
     * The holder of the ticket at {@code uniform} along all tickets in a row — each ticket an equal share.
     *
     * @param uniform a number in [0, 1)
     * @return null when nobody holds a ticket
     */
    public UUID winner(Map<UUID, Integer> tickets, double uniform) {
        int total = tickets.values().stream().mapToInt(Integer::intValue).sum();
        if (total <= 0) {
            return null;
        }
        long pick = Math.min(total - 1, (long) Math.floor(Math.max(0, uniform) * total));
        long passed = 0;
        UUID last = null;
        for (Map.Entry<UUID, Integer> each : tickets.entrySet()) {
            passed += each.getValue();
            last = each.getKey();
            if (pick < passed) {
                return each.getKey();
            }
        }
        return last;
    }

    public double chance(int mine, int total) {
        return total <= 0 ? 0 : mine / (double) total;
    }

    /** How many of {@code wanted} may be bought; a limit of zero is no limit. */
    public int allowed(int wanted, int mine, int perPlayer, int sold, int mostTickets) {
        int count = Math.max(0, wanted);
        if (perPlayer > 0) {
            count = Math.min(count, perPlayer - mine);
        }
        if (mostTickets > 0) {
            count = Math.min(count, mostTickets - sold);
        }
        return Math.max(0, count);
    }

    /** The house's share of the pot, at most half of it. */
    public Money fee(Money pot, double percent) {
        double share = Math.max(0, Math.min(50, percent));
        return Money.of((long) Math.floor(pot.minor() * share / 100.0));
    }

    @Override
    public String describe() {
        return "who a raffle's draw picks and how many tickets anybody may buy";
    }
}
