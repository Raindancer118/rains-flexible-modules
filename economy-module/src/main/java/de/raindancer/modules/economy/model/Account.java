package de.raindancer.modules.economy.model;

import de.raindancer.core.social.economy.Money;

import java.util.UUID;

/**
 * One player's account as it stands. Immutable; the ledger replaces it whole on every change.
 *
 * @param dailyDay    the epoch day /daily was last claimed, or -1 for never
 * @param dailyStreak days in a row, counting that one
 */
public record Account(UUID id, String name, Money balance, boolean frozen, long created, long dailyDay,
                      int dailyStreak) {

    public Account {
        name = name == null || name.isBlank() ? id.toString().substring(0, 8) : name;
        balance = balance == null ? Money.ZERO : balance;
    }

    public static Account opened(UUID id, String name, Money starting, long now) {
        return new Account(id, name, starting, false, now, -1, 0);
    }

    public Account withBalance(Money value) {
        return new Account(id, name, value, frozen, created, dailyDay, dailyStreak);
    }

    public Account withName(String value) {
        return new Account(id, value, balance, frozen, created, dailyDay, dailyStreak);
    }

    public Account withFrozen(boolean value) {
        return new Account(id, name, balance, value, created, dailyDay, dailyStreak);
    }

    public Account withDaily(long day, int streak) {
        return new Account(id, name, balance, frozen, created, day, streak);
    }
}
