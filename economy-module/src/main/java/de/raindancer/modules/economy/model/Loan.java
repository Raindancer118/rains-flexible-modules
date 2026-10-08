package de.raindancer.modules.economy.model;

import de.raindancer.core.social.economy.Money;

import java.util.UUID;

/**
 * Money a player borrowed from the bank. One per player.
 *
 * @param borrowed what was paid out
 * @param owed     what is still to be paid back, interest and late fees included
 * @param dueAt    when it has to be paid back; after that the bank collects from the balance
 * @param lateAt   up to when late fees have been added; before the due date it means nothing
 */
public record Loan(UUID player, String name, Money borrowed, Money owed, long takenAt, long dueAt, long lateAt) {

    public boolean overdue(long now) {
        return now >= dueAt;
    }

    public Loan owing(Money stillOwed, long feesUpTo) {
        return new Loan(player, name, borrowed, stillOwed, takenAt, dueAt, feesUpTo);
    }

    public Loan paid(Money amount) {
        return owing(owed.minus(amount.min(owed)), lateAt);
    }

    public boolean settled() {
        return !owed.isPositive();
    }
}
