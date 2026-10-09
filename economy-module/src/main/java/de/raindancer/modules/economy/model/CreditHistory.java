package de.raindancer.modules.economy.model;

import de.raindancer.core.social.economy.Money;

import java.util.EnumMap;
import java.util.Map;

/**
 * Everything one account has ever taken in and paid out, by kind, and how its loans ended — kept for life,
 * unlike the statement, which is trimmed after a while.
 *
 * @param repaidOnTime loans paid off by their due date
 * @param repaidLate   loans paid off, or collected, after it
 */
public record CreditHistory(Map<TransactionKind, Money> in, Map<TransactionKind, Money> out, int repaidOnTime,
                            int repaidLate, Recent recent) {

    public static final CreditHistory EMPTY = new CreditHistory(Map.of(), Map.of(), 0, 0, Recent.NONE);

    /**
     * The last hours of playtime only: income, spending, money staked and won in games of chance, and money
     * from and to other players (kept apart so the two can be netted).
     *
     * <p>Playtime rather than days on the calendar, so a week away does not wipe a losing streak clean.
     */
    public record Recent(Money earned, Money spent, Money staked, Money won, Money received, Money paid) {

        public static final Recent NONE = new Recent(Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO,
                Money.ZERO);

        public Recent plus(Recent other) {
            return new Recent(earned.plus(other.earned), spent.plus(other.spent), staked.plus(other.staked),
                    won.plus(other.won), received.plus(other.received), paid.plus(other.paid));
        }
    }

    public CreditHistory {
        in = in == null || in.isEmpty() ? Map.of() : Map.copyOf(new EnumMap<>(in));
        out = out == null || out.isEmpty() ? Map.of() : Map.copyOf(new EnumMap<>(out));
        recent = recent == null ? Recent.NONE : recent;
    }

    public CreditHistory withRecent(Recent value) {
        return new CreditHistory(in, out, repaidOnTime, repaidLate, value);
    }

    public Money in(TransactionKind kind) {
        return in.getOrDefault(kind, Money.ZERO);
    }

    public Money out(TransactionKind kind) {
        return out.getOrDefault(kind, Money.ZERO);
    }

    /** This history with one more statement line counted. */
    public CreditHistory with(Transaction line) {
        Map<TransactionKind, Money> nextIn = new EnumMap<>(TransactionKind.class);
        Map<TransactionKind, Money> nextOut = new EnumMap<>(TransactionKind.class);
        nextIn.putAll(in);
        nextOut.putAll(out);
        if (line.delta().isPositive()) {
            nextIn.merge(line.kind(), line.delta(), Money::plus);
        } else if (line.delta().isNegative()) {
            nextOut.merge(line.kind(), line.delta().negate(), Money::plus);
        }
        return new CreditHistory(nextIn, nextOut, repaidOnTime, repaidLate, recent);
    }

    public CreditHistory withLoanEnded(boolean onTime) {
        return new CreditHistory(in, out, repaidOnTime + (onTime ? 1 : 0), repaidLate + (onTime ? 0 : 1), recent);
    }
}
