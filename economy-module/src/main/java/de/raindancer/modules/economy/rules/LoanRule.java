package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.Loan;
import de.raindancer.modules.economy.model.LoanRefusal;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Loans: interest added once when borrowing, a late fee for every whole day past the due date (on what is
 * still owed, so it compounds), and the bank taking what the balance holds once it is due. Fractions of a
 * coin always round toward the bank.
 */
public final class LoanRule implements IEconomyRule {

    public static final long DAY = 86_400_000L;
    private static final StakeRule ROUNDING = new StakeRule();

    public Money owedFor(Money borrowed, double rate) {
        return borrowed.plus(upShare(borrowed, rate));
    }

    public Optional<LoanRefusal> refusal(Money amount, Money least, Money most, boolean hasLoan) {
        if (hasLoan) {
            return Optional.of(LoanRefusal.HAS_LOAN);
        }
        if (!amount.isPositive() || !amount.isAtLeast(least)) {
            return Optional.of(LoanRefusal.TOO_LITTLE);
        }
        if (most.isPositive() && amount.isMoreThan(most)) {
            return Optional.of(LoanRefusal.TOO_MUCH);
        }
        return Optional.empty();
    }

    /** The loan with every whole overdue day's fee added that has not been added yet. */
    public Loan withLateFees(Loan loan, long now, double latePerDay) {
        long from = Math.max(loan.dueAt(), loan.lateAt());
        if (now < loan.dueAt() || now - from < DAY) {
            return loan;
        }
        long days = (now - from) / DAY;
        Money owed = loan.owed();
        if (latePerDay > 0) {
            for (long day = 0; day < days && owed.isPositive(); day++) {
                owed = owed.plus(upShare(owed, latePerDay));
            }
        }
        return loan.owing(owed, from + days * DAY);
    }

    public Money collect(Money balance, Money owed) {
        return balance.isPositive() ? balance.min(owed) : Money.ZERO;
    }

    /** A few round amounts to offer between the least and the most, rising, the most always last. */
    public List<Money> offers(Money least, Money most) {
        List<Money> out = new ArrayList<>();
        for (double share : new double[]{0.1, 0.25, 0.5}) {
            Money each = ROUNDING.nice(most.share(share)).max(least);
            if (each.isPositive() && most.isMoreThan(each) && (out.isEmpty() || each.isMoreThan(out.getLast()))) {
                out.add(each);
            }
        }
        if (most.isPositive()) {
            out.add(most);
        }
        return out;
    }

    private static Money upShare(Money amount, double rate) {
        if (!(rate > 0)) {
            return Money.ZERO;
        }
        return Money.of((long) Math.ceil(amount.minor() * rate - 1e-9));
    }

    @Override
    public String describe() {
        return "what a loan costs, when it is refused, and what the bank collects";
    }
}
