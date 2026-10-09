package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.CreditHistory;
import de.raindancer.modules.economy.model.TransactionKind;

import java.util.Set;

/**
 * How much the bank lends one player: what they have, a share of everything they ever earned, cut by
 * how much of it they spend and gamble away, and moved by how their earlier loans ended — never past the
 * server's largest loan.
 *
 * <h2>Why money between players is netted</h2>
 * Two friends paying the same coins back and forth would otherwise both look like great earners. Payments,
 * bills and auctions count only as what came in more than went out (earned), or the other way round (spent).
 *
 * <h2>Why the highest balance ever does not count</h2>
 * A loan itself, or a friend's money held for a minute, would push it up — and the next loan with it.
 */
public final class CreditRule implements IEconomyRule {

    static final double EARNED_SHARE = 0.25;
    static final double LEAST_FACTOR = 0.25;
    static final double ON_TIME_BONUS = 0.1;
    static final double LATE_PENALTY = 0.3;
    static final double MOST_RECORD = 2.0;

    public static final Set<TransactionKind> EARNING = Set.of(TransactionKind.SELL, TransactionKind.REWARD,
            TransactionKind.INCOME, TransactionKind.WAGE, TransactionKind.DAILY, TransactionKind.INTEREST);
    private static final Set<TransactionKind> SPENDING = Set.of(TransactionKind.BUY, TransactionKind.TAX,
            TransactionKind.FEE);
    private static final Set<TransactionKind> BETWEEN_PLAYERS = Set.of(TransactionKind.PAY, TransactionKind.BILL,
            TransactionKind.AUCTION, TransactionKind.PLUGIN);
    public static final Set<TransactionKind> GAMBLING = Set.of(TransactionKind.GAMBLE, TransactionKind.LOTTERY,
            TransactionKind.RAFFLE);

    /**
     * Items do not count: what somebody carries says nothing — the same things in a chest at home would be
     * worth nothing here, so counting them would only reward walking around with a full inventory.
     */
    public record Standing(Money balance, CreditHistory history) {
    }

    /** The limit, and every number it came from — for the screen that explains it. */
    public record Limit(Money amount, Money capacity, Money earned, Money spent, Money gambledAway,
                        double spending, double gambling, double record, Money lostLately, double lately) {
    }

    public Limit limit(Standing standing, Money most) {
        CreditHistory history = standing.history();
        long earned = 0;
        long spent = 0;
        long staked = 0;
        long won = 0;
        long between = 0;
        for (TransactionKind kind : TransactionKind.values()) {
            long in = history.in(kind).minor();
            long out = history.out(kind).minor();
            if (EARNING.contains(kind)) {
                earned += in;
            } else if (SPENDING.contains(kind)) {
                spent += out;
            } else if (BETWEEN_PLAYERS.contains(kind)) {
                between += in - out;
            } else if (GAMBLING.contains(kind)) {
                won += in;
                staked += out;
            }
        }
        earned += Math.max(0, between);
        spent += Math.max(0, -between);
        // A lifetime of winning counts like earning; a lifetime of losing cuts below.
        earned += Math.max(0, won - staked);
        long gambledAway = Math.max(0, staked - won);
        CreditHistory.Recent recent = history.recent();
        long lostLately = Math.max(0, recent.staked().minor() - recent.won().minor());

        long capacity = Math.max(0, standing.balance().minor())
                + Math.round(earned * EARNED_SHARE);
        double spending = earned + spent == 0 ? 1.0 : 0.5 + 0.5 * earned / (double) (earned + spent);
        double gambling = gambledAway == 0 ? 1.0
                : Math.max(LEAST_FACTOR, 1.0 - gambledAway / (double) Math.max(1, earned));
        // What was lost lately weighs against what is there now and what came in lately, not against a
        // lifetime: winning big a month ago does not cover being broke every evening since.
        double lately = lostLately == 0 ? 1.0 : Math.max(LEAST_FACTOR, 1.0 - lostLately
                / (double) Math.max(1, Math.max(0, standing.balance().minor()) + recent.earned().minor()));
        double record = Math.max(LEAST_FACTOR, Math.min(MOST_RECORD,
                1.0 + ON_TIME_BONUS * history.repaidOnTime() - LATE_PENALTY * history.repaidLate()));

        long amount = roundDown((long) Math.floor(capacity * spending * gambling * lately * record));
        if (most.isPositive()) {
            amount = Math.min(amount, most.minor());
        }
        return new Limit(Money.of(amount), Money.of(capacity), Money.of(earned), Money.of(spent),
                Money.of(gambledAway), spending, gambling, record, Money.of(lostLately), lately);
    }

    /** Down to two significant digits: 12,345 → 12,000. */
    static long roundDown(long value) {
        if (value < 100) {
            return Math.max(0, value);
        }
        long step = 1;
        while (value / step >= 100) {
            step *= 10;
        }
        return value / step * step;
    }

    @Override
    public String describe() {
        return "how much the bank lends one player, from their money and their record";
    }
}
