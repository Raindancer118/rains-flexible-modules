package de.raindancer.modules.economy.service;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Economy;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.model.Account;
import de.raindancer.modules.economy.model.TransactionKind;
import de.raindancer.modules.economy.store.AccountBook;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * This module's money, as RainsCore's {@link Economy} — what every other plugin charges through, and
 * through Core's Vault bridge what every Vault plugin does too. Inside the module, {@link #move} and
 * {@link #transfer} say why money moved, so a statement reads "Bought" rather than "Another plugin".
 */
public final class RainEconomy implements Economy, IEconomyService {

    public static final String NAME = "RainsEconomy";

    /** Told about every balance that changed, after it changed. */
    @FunctionalInterface
    public interface BalanceWatcher {
        void moved(UUID who, Money delta, Money balance, TransactionKind kind);
    }

    private final AccountBook book;
    private final Function<UUID, String> names;
    private final List<BalanceWatcher> watchers = new CopyOnWriteArrayList<>();
    private volatile EconomySettings settings;
    private volatile Currency currency;

    /** @param names a player's name by id, or null for nobody the server has seen */
    public RainEconomy(AccountBook book, Function<UUID, String> names, EconomySettings settings) {
        this.book = book;
        this.names = names;
        settings(settings);
    }

    @Override
    public void settings(EconomySettings updated) {
        this.settings = updated == null ? EconomySettings.DEFAULTS : updated;
        this.currency = this.settings.currency();
    }

    public void watch(BalanceWatcher watcher) {
        watchers.add(watcher);
    }

    public AccountBook book() {
        return book;
    }

    public Money most() {
        return settings.most();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Currency currency() {
        return currency;
    }

    public Account open(UUID id, String name) {
        return book.open(id, name, settings.starting());
    }

    @Override
    public boolean hasAccount(UUID player) {
        return book.find(player).isPresent();
    }

    @Override
    public boolean createAccount(UUID player) {
        return ensure(player);
    }

    /** Opens an account for anybody the server has seen, so another plugin paying them is not refused. */
    private boolean ensure(UUID player) {
        if (book.find(player).isPresent()) {
            return true;
        }
        String name = names.apply(player);
        if (name == null) {
            return false;
        }
        open(player, name);
        return true;
    }

    @Override
    public Money balance(UUID player) {
        return book.balance(player);
    }

    @Override
    public EconomyResult deposit(UUID player, Money amount, String reason) {
        if (amount.isZero()) {
            return EconomyResult.done(amount, balance(player));
        }
        ensure(player);
        return move(player, amount, TransactionKind.PLUGIN, reason);
    }

    @Override
    public EconomyResult withdraw(UUID player, Money amount, String reason) {
        if (amount.isZero()) {
            return EconomyResult.done(amount, balance(player));
        }
        if (amount.isNegative()) {
            return EconomyResult.failed(EconomyResult.Outcome.INVALID_AMOUNT, amount, balance(player));
        }
        ensure(player);
        return move(player, amount.negate(), TransactionKind.PLUGIN, reason);
    }

    @Override
    public EconomyResult transfer(UUID from, UUID to, Money amount, String reason) {
        ensure(from);
        ensure(to);
        return transfer(from, to, amount, Money.ZERO, TransactionKind.PLUGIN, reason);
    }

    /** Money arriving (positive) or leaving (negative), for a reason the statement can name. */
    public EconomyResult move(UUID player, Money delta, TransactionKind kind, String reason) {
        if (delta.isZero()) {
            return EconomyResult.failed(EconomyResult.Outcome.INVALID_AMOUNT, delta, balance(player));
        }
        EconomyResult result = book.change(player, delta, kind, reason, null, settings.most());
        if (result.succeeded()) {
            tell(player, delta, result.balance(), kind);
        }
        return result;
    }

    public EconomyResult transfer(UUID from, UUID to, Money amount, Money tax, TransactionKind kind, String reason) {
        EconomyResult result = book.transfer(from, to, amount, tax, kind, reason, settings.most());
        if (result.succeeded()) {
            tell(from, amount.negate(), result.balance(), kind);
            tell(to, amount.minus(tax.min(amount)), book.balance(to), kind);
        }
        return result;
    }

    /** Anything that changed a balance through the book directly — cash, /daily — tells watchers here. */
    public void tell(UUID who, Money delta, Money balance, TransactionKind kind) {
        for (BalanceWatcher watcher : watchers) {
            try {
                watcher.moved(who, delta, balance, kind);
            } catch (RuntimeException ignored) {
                // A watcher is a courtesy — the money has already moved.
            }
        }
    }
}
