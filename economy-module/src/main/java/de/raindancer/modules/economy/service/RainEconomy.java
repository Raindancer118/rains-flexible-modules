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
    private volatile SupplyService supply;

    /** Payouts that print money: a share of each goes toward a debt the player owes the server. */
    private static final java.util.Set<TransactionKind> INCOME = java.util.EnumSet.of(TransactionKind.REWARD,
            TransactionKind.INCOME, TransactionKind.INTEREST, TransactionKind.SELL, TransactionKind.DAILY,
            TransactionKind.PLUGIN);

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

    /** The money supply's settings and snapshot; until wired, the economy behaves as it always did. */
    public void supply(SupplyService service) {
        this.supply = service;
    }

    @Override
    public java.util.Optional<de.raindancer.core.social.economy.MoneySupply> supply() {
        SupplyService service = supply;
        return service == null ? java.util.Optional.of(book.supply(0)) : java.util.Optional.of(service.snapshot());
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
    public EconomyResult deposit(UUID player, Money amount, String reason, String source) {
        if (amount.isZero()) {
            return EconomyResult.done(amount, balance(player));
        }
        ensure(player);
        return move(player, amount, TransactionKind.PLUGIN, reason, source);
    }

    @Override
    public EconomyResult refund(UUID player, Money amount, String reason, String source) {
        if (amount.isZero()) {
            return EconomyResult.done(amount, balance(player));
        }
        ensure(player);
        EconomyResult result = book.restore(player, amount, TransactionKind.PLUGIN, reason, source);
        if (result.succeeded()) {
            tell(player, amount, result.balance(), TransactionKind.PLUGIN);
        }
        return result;
    }

    /** Pays a win as far as the treasury can; the result's amount is what was paid. */
    public EconomyResult moveUpTo(UUID player, Money amount, TransactionKind kind, String reason) {
        EconomyResult result = book.changeUpTo(player, amount, kind, reason, settings.most());
        if (result.succeeded()) {
            tell(player, result.amount(), result.balance(), kind);
        }
        return result;
    }

    @Override
    public EconomyResult withdraw(UUID player, Money amount, String reason, String source) {
        if (amount.isZero()) {
            return EconomyResult.done(amount, balance(player));
        }
        if (amount.isNegative()) {
            return EconomyResult.failed(EconomyResult.Outcome.INVALID_AMOUNT, amount, balance(player));
        }
        ensure(player);
        return move(player, amount.negate(), TransactionKind.PLUGIN, reason, source);
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
        return move(player, delta, kind, reason, "");
    }

    /**
     * {@link #move}, saying where the money came from or went. A payout to somebody who owes the server has the
     * owner's share taken toward the debt straight after — the result still says what was paid.
     */
    public EconomyResult move(UUID player, Money delta, TransactionKind kind, String reason, String source) {
        if (delta.isZero()) {
            return EconomyResult.failed(EconomyResult.Outcome.INVALID_AMOUNT, delta, balance(player));
        }
        EconomyResult result = book.change(player, delta, kind, reason, null, settings.most(), source);
        if (result.succeeded()) {
            tell(player, delta, result.balance(), kind);
            if (delta.isPositive() && INCOME.contains(kind)) {
                collectDebt(player, delta);
            }
        }
        return result;
    }

    /** Takes the owner's share of a payout toward what the player owes, and hands it to whoever keeps the debt. */
    void collectDebt(UUID player, Money paid) {
        SupplyService service = supply;
        int percent = service == null ? de.raindancer.modules.economy.SupplySettings.DEFAULTS.debtSharePercent()
                : service.current().debtSharePercent();
        if (percent <= 0 || !de.raindancer.core.social.economy.Debts.inDebt(player)) {
            return;
        }
        Money share = paid.share(Math.min(100, percent) / 100.0)
                .min(de.raindancer.core.social.economy.Debts.owed(player));
        if (!share.isPositive()) {
            return;
        }
        EconomyResult taken = book.change(player, share.negate(), TransactionKind.DEBT, "", null, settings.most());
        if (!taken.succeeded()) {
            return;
        }
        Money settled = de.raindancer.core.social.economy.Debts.collected(player, share);
        Money unused = share.minus(settled);
        if (unused.isPositive()) {
            // A keeper that settled less than offered: what it did not take goes back, exactly.
            book.restore(player, unused, TransactionKind.DEBT, "Not needed for the debt", "");
        }
        tell(player, settled.negate(), book.balance(player), TransactionKind.DEBT);
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
