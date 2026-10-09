package de.raindancer.modules.economy.model;

import de.raindancer.core.social.economy.Money;

import java.util.UUID;

/**
 * One player's account as it stands. Immutable; the ledger replaces it whole on every change.
 *
 * @param dailyDay    the epoch day /daily was last claimed, or -1 for never
 * @param dailyStreak days in a row, counting that one
 * @param lastSpent   when money last left the account other than by tax; 0 for never
 */
public record Account(UUID id, String name, Money balance, boolean frozen, long created, long dailyDay,
                      int dailyStreak, long lastSpent) {

    public Account {
        name = name == null || name.isBlank() ? id.toString().substring(0, 8) : name;
        balance = balance == null ? Money.ZERO : balance;
    }

    public Account(UUID id, String name, Money balance, boolean frozen, long created, long dailyDay, int dailyStreak) {
        this(id, name, balance, frozen, created, dailyDay, dailyStreak, 0);
    }

    public static Account opened(UUID id, String name, Money starting, long now) {
        return new Account(id, name, starting, false, now, -1, 0, now);
    }

    public Account withLastSpent(long at) {
        return new Account(id, name, balance, frozen, created, dailyDay, dailyStreak, at);
    }

    /** When money last moved out of it on purpose, or when it was opened if it never has. */
    public long idleSince() {
        return Math.max(created, lastSpent);
    }

    public Account withBalance(Money value) {
        return new Account(id, name, value, frozen, created, dailyDay, dailyStreak, lastSpent);
    }

    public Account withName(String value) {
        return new Account(id, value, balance, frozen, created, dailyDay, dailyStreak, lastSpent);
    }

    public Account withFrozen(boolean value) {
        return new Account(id, name, balance, value, created, dailyDay, dailyStreak, lastSpent);
    }

    public Account withDaily(long day, int streak) {
        return new Account(id, name, balance, frozen, created, day, streak, lastSpent);
    }
}
