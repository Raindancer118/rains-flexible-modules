package de.raindancer.modules.economy.service;

import de.raindancer.modules.economy.model.Account;
import de.raindancer.modules.economy.store.AccountBook;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

/** The richest accounts, sorted at most every few seconds however often /baltop is typed. */
public final class LeaderboardService {

    private static final long FRESH_FOR_MILLIS = 10_000;

    private final AccountBook book;
    private final LongSupplier clock;
    private volatile List<Account> sorted = List.of();
    private volatile long sortedAt = Long.MIN_VALUE / 2;

    public LeaderboardService(AccountBook book, LongSupplier clock) {
        this.book = book;
        this.clock = clock;
    }

    public List<Account> ranking() {
        long now = clock.getAsLong();
        if (now - sortedAt > FRESH_FOR_MILLIS) {
            sorted = book.all().stream().filter(account -> !AccountBook.isSystem(account.id()))
                    .sorted(Comparator.comparing(Account::balance).reversed().thenComparing(Account::name))
                    .toList();
            sortedAt = now;
        }
        return sorted;
    }

    /** 1-based place, or 0 for no account. */
    public int placeOf(UUID id) {
        List<Account> all = ranking();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id().equals(id)) {
                return i + 1;
            }
        }
        return 0;
    }
}
