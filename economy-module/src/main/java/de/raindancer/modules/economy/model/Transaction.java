package de.raindancer.modules.economy.model;

import de.raindancer.core.social.economy.Money;

import java.util.UUID;

/**
 * One line of one account's statement. A transfer writes two: one for each side.
 *
 * @param other   the account on the other side, or null when money came from or went to nowhere
 * @param delta   positive arriving, negative leaving
 * @param balance what the account held afterwards
 */
public record Transaction(long at, UUID account, UUID other, Money delta, Money balance, TransactionKind kind,
                          String reason) {

    public Transaction {
        reason = reason == null ? "" : reason;
        kind = kind == null ? TransactionKind.PLUGIN : kind;
    }
}
