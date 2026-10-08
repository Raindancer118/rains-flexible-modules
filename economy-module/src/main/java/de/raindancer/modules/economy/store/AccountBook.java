package de.raindancer.modules.economy.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.EconomyResult.Outcome;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.Account;
import de.raindancer.modules.economy.model.BalanceChange;
import de.raindancer.modules.economy.model.Contract;
import de.raindancer.modules.economy.model.Payday;
import de.raindancer.modules.economy.model.DailyClaim;
import de.raindancer.modules.economy.model.Transaction;
import de.raindancer.modules.economy.model.TransactionKind;
import de.raindancer.modules.economy.rules.BalanceRule;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Every account, every statement line and every outstanding banknote — the one place money changes.
 *
 * <h2>How it stays right</h2>
 * Everything lives in memory behind one lock, so a balance check and the change it guards can never be
 * split by another thread: two payments from one account in the same millisecond see each other. Every
 * change is journaled as statement lines, and {@link #flush} writes accounts, lines and notes in one
 * transaction — a crash loses at most what happened since the last flush, never half of a transfer.
 * A failed write is put back and tried again rather than dropped.
 *
 * <h2>Why notes are in here</h2>
 * Taking money out as a note and registering the note's serial must be one change, or a crash between
 * the two is money that left the account and a note nobody will accept — or the reverse.
 */
public final class AccountBook {

    private record NoteWrite(String serial, Money value, UUID issuedTo, long issuedAt, UUID redeemedBy,
                             long redeemedAt) {
    }

    private final Database database;
    private final BalanceRule rule;
    private final LongSupplier clock;

    private final Object lock = new Object();
    private final Map<UUID, Account> accounts = new HashMap<>();
    private final Map<String, Money> outstanding = new HashMap<>();
    /** Coins in circulation, by value: issued and not yet paid back in. More cannot be paid in. */
    private final Map<Money, Long> circulation = new HashMap<>();
    private final Set<Money> dirtyCoins = new LinkedHashSet<>();
    private final Map<UUID, Contract> contracts = new LinkedHashMap<>();
    private final Set<UUID> dirtyContracts = new LinkedHashSet<>();
    private final Set<UUID> dirty = new LinkedHashSet<>();
    private final List<Transaction> journal = new ArrayList<>();
    private final List<NoteWrite> notes = new ArrayList<>();
    private volatile boolean loaded;

    /** The lottery's pot is an account of its own, so tickets bought and the pot are one ledger. */
    public static final UUID LOTTERY_POT = new UUID(0L, 0x1077E7L);
    private static final Money SYSTEM_MOST = Money.of(Long.MAX_VALUE / 4);
    private long draw = 1;
    private long nextDrawAt;
    private final Map<UUID, Integer> tickets = new HashMap<>();
    private final Set<UUID> dirtyTickets = new LinkedHashSet<>();
    private boolean lotteryDirty;
    private long clearedDraw = -1;

    /** One flush at a time, so lines are written in the order they happened. */
    private final ReentrantLock flushing = new ReentrantLock();

    public AccountBook(Database database, BalanceRule rule, LongSupplier clock) {
        this.database = database;
        this.rule = rule;
        this.clock = clock;
    }

    // ---------------------------------------------------------------------------- loading

    /** Reads every account and every outstanding note. Off the server's threads. */
    public boolean load() {
        Optional<Boolean> read = database.read(connection -> {
            Map<UUID, Account> found = new HashMap<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT id, name, balance, frozen, created, daily_day, daily_streak FROM account");
                 ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    UUID id = UUID.fromString(rows.getString(1));
                    found.put(id, new Account(id, rows.getString(2), Money.of(rows.getLong(3)),
                            rows.getInt(4) != 0, rows.getLong(5), rows.getLong(6), rows.getInt(7)));
                }
            }
            Map<String, Money> open = new HashMap<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT serial, value FROM note WHERE redeemed_at IS NULL");
                 ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    open.put(rows.getString(1), Money.of(rows.getLong(2)));
                }
            }
            Map<Money, Long> coinsOut = new HashMap<>();
            try (PreparedStatement select = connection.prepareStatement("SELECT value, outstanding FROM coin_float");
                 ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    coinsOut.put(Money.of(rows.getLong(1)), rows.getLong(2));
                }
            }
            List<Contract> jobs = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT id, employer, employer_name, employee, employee_name, wage, every, next_at, missed, title, "
                            + "since FROM contract ORDER BY since");
                 ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    jobs.add(new Contract(UUID.fromString(rows.getString(1)), UUID.fromString(rows.getString(2)),
                            rows.getString(3), UUID.fromString(rows.getString(4)), rows.getString(5),
                            Money.of(rows.getLong(6)), rows.getInt(7), rows.getLong(8), rows.getInt(9),
                            rows.getString(10), rows.getLong(11)));
                }
            }
            long drawRead = 1;
            long nextRead = 0;
            try (PreparedStatement select = connection.prepareStatement("SELECT draw, next_at FROM lottery WHERE id = 1");
                 ResultSet rows = select.executeQuery()) {
                if (rows.next()) {
                    drawRead = rows.getLong(1);
                    nextRead = rows.getLong(2);
                }
            }
            Map<UUID, Integer> held = new HashMap<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT player, tickets FROM lottery_ticket WHERE draw = ?")) {
                select.setLong(1, drawRead);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        held.put(UUID.fromString(rows.getString(1)), rows.getInt(2));
                    }
                }
            }
            synchronized (lock) {
                accounts.putAll(found);
                outstanding.putAll(open);
                circulation.putAll(coinsOut);
                jobs.forEach(job -> contracts.put(job.id(), job));
                draw = drawRead;
                nextDrawAt = nextRead;
                tickets.putAll(held);
            }
            return true;
        });
        loaded = read.orElse(false);
        if (loaded) {
            open(LOTTERY_POT, "Lottery pot", Money.ZERO);
        }
        return loaded;
    }

    /** Whether the accounts are in memory. Until they are, every change is refused as unavailable. */
    public boolean isLoaded() {
        return loaded;
    }

    // ---------------------------------------------------------------------------- reading

    public Optional<Account> find(UUID id) {
        synchronized (lock) {
            return Optional.ofNullable(accounts.get(id));
        }
    }

    public Optional<Account> findByName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        synchronized (lock) {
            return accounts.values().stream().filter(account -> account.name().equalsIgnoreCase(name)).findFirst();
        }
    }

    public Money balance(UUID id) {
        synchronized (lock) {
            Account account = accounts.get(id);
            return account == null ? Money.ZERO : account.balance();
        }
    }

    public List<Account> all() {
        synchronized (lock) {
            return List.copyOf(accounts.values());
        }
    }

    public boolean isOutstanding(String serial) {
        synchronized (lock) {
            return outstanding.containsKey(serial);
        }
    }

    /** How many coins of this value are out in the world. */
    public long coinsOut(Money value) {
        synchronized (lock) {
            return circulation.getOrDefault(value, 0L);
        }
    }

    public Optional<Money> noteValue(String serial) {
        synchronized (lock) {
            return Optional.ofNullable(outstanding.get(serial));
        }
    }

    // ---------------------------------------------------------------------------- accounts

    /** Opens an account unless there is one; a changed name is kept up to date either way. */
    public Account open(UUID id, String name, Money starting) {
        synchronized (lock) {
            Account account = accounts.get(id);
            if (account != null) {
                if (name != null && !name.isBlank() && !name.equals(account.name())) {
                    account = account.withName(name);
                    accounts.put(id, account);
                    dirty.add(id);
                }
                return account;
            }
            long now = clock.getAsLong();
            Money opening = starting.isNegative() ? Money.ZERO : starting;
            account = Account.opened(id, name, opening, now);
            accounts.put(id, account);
            dirty.add(id);
            journal.add(new Transaction(now, id, null, opening, opening, TransactionKind.OPENING, ""));
            return account;
        }
    }

    public boolean freeze(UUID id, boolean frozen) {
        synchronized (lock) {
            Account account = accounts.get(id);
            if (account == null) {
                return false;
            }
            accounts.put(id, account.withFrozen(frozen));
            dirty.add(id);
            return true;
        }
    }

    // ---------------------------------------------------------------------------- moving money

    /**
     * Adds or takes money from one account.
     *
     * @param delta positive arriving, negative leaving
     * @param other the account on the other side, for the statement; may be null
     */
    public EconomyResult change(UUID id, Money delta, TransactionKind kind, String reason, UUID other, Money most) {
        synchronized (lock) {
            Optional<EconomyResult> refused = refuseEarly(id, delta.isNegative() ? delta.negate() : delta);
            if (refused.isPresent()) {
                return refused.get();
            }
            Account account = accounts.get(id);
            BalanceChange change = rule.apply(account.balance(), delta, most, account.frozen());
            if (!change.allowed()) {
                return EconomyResult.failed(change.outcome(), abs(delta), account.balance());
            }
            commit(account, change.after(), delta, kind, reason, other);
            return EconomyResult.done(abs(delta), change.after());
        }
    }

    /**
     * Moves money from one account to another, both or neither.
     *
     * @param tax taken from what arrives and recorded on the receiver's statement as its own line
     */
    public EconomyResult transfer(UUID from, UUID to, Money amount, Money tax, TransactionKind kind, String reason,
                                  Money most) {
        synchronized (lock) {
            Optional<EconomyResult> refused = refuseEarly(from, amount);
            if (refused.isPresent()) {
                return refused.get();
            }
            Account sender = accounts.get(from);
            Account receiver = accounts.get(to);
            if (receiver == null) {
                return EconomyResult.failed(Outcome.NO_ACCOUNT, amount, sender.balance());
            }
            if (from.equals(to)) {
                return EconomyResult.failed(Outcome.REFUSED, amount, sender.balance());
            }
            Money arriving = amount.minus(tax.min(amount));
            BalanceChange leaving = rule.apply(sender.balance(), amount.negate(), most, sender.frozen());
            if (!leaving.allowed()) {
                return EconomyResult.failed(leaving.outcome(), amount, sender.balance());
            }
            if (receiver.frozen()) {
                return EconomyResult.failed(Outcome.FROZEN, amount, sender.balance());
            }
            BalanceChange landing = arriving.isZero()
                    ? new BalanceChange(Outcome.DONE, receiver.balance())
                    : rule.apply(receiver.balance(), arriving, most, false);
            if (!landing.allowed()) {
                return EconomyResult.failed(landing.outcome(), amount, sender.balance());
            }
            commit(sender, leaving.after(), amount.negate(), kind, reason, to);
            Account received = accounts.get(to);
            long now = clock.getAsLong();
            Money afterPayment = received.balance().plus(amount);
            journal.add(new Transaction(now, to, from, amount, afterPayment, kind, reason));
            if (!tax.isZero()) {
                journal.add(new Transaction(now, to, null, tax.min(amount).negate(), landing.after(),
                        TransactionKind.TAX, ""));
            }
            accounts.put(to, received.withBalance(landing.after()));
            dirty.add(to);
            return EconomyResult.done(amount, leaving.after());
        }
    }

    /** Staff setting a balance outright. Recorded as the difference, so a statement still adds up. */
    public EconomyResult set(UUID id, Money value, String reason) {
        synchronized (lock) {
            Account account = accounts.get(id);
            if (account == null) {
                return EconomyResult.failed(Outcome.NO_ACCOUNT, value, Money.ZERO);
            }
            if (value.isNegative()) {
                return EconomyResult.failed(Outcome.INVALID_AMOUNT, value, account.balance());
            }
            Money delta = value.minus(account.balance());
            if (!delta.isZero()) {
                commit(account, value, delta, TransactionKind.ADMIN, reason, null);
            }
            return EconomyResult.done(value, value);
        }
    }

    /**
     * Takes money out as cash: the total and the fee leave the account, and every note is registered as
     * outstanding, in one change.
     *
     * @param notes serial to value, for the numbered pieces
     * @param coins how many coins of each value, added to what is in circulation
     */
    public EconomyResult issueCash(UUID id, Map<String, Money> notes, Map<Money, Integer> coins, Money total, Money fee,
                                   Money most) {
        synchronized (lock) {
            Optional<EconomyResult> refused = refuseEarly(id, total);
            if (refused.isPresent()) {
                return refused.get();
            }
            Account account = accounts.get(id);
            Money cost;
            try {
                cost = total.plus(fee);
            } catch (ArithmeticException overflow) {
                return EconomyResult.failed(Outcome.INVALID_AMOUNT, total, account.balance());
            }
            BalanceChange change = rule.apply(account.balance(), cost.negate(), most, account.frozen());
            if (!change.allowed()) {
                return EconomyResult.failed(change.outcome(), total, account.balance());
            }
            for (String serial : notes.keySet()) {
                if (outstanding.containsKey(serial)) {
                    return EconomyResult.failed(Outcome.REFUSED, total, account.balance());
                }
            }
            long now = clock.getAsLong();
            Money afterTotal = account.balance().minus(total);
            journal.add(new Transaction(now, id, null, total.negate(), afterTotal, TransactionKind.WITHDRAW, ""));
            if (fee.isPositive()) {
                journal.add(new Transaction(now, id, null, fee.negate(), change.after(), TransactionKind.FEE, ""));
            }
            accounts.put(id, account.withBalance(change.after()));
            dirty.add(id);
            notes.forEach((serial, value) -> {
                outstanding.put(serial, value);
                this.notes.add(new NoteWrite(serial, value, id, now, null, 0));
            });
            coins.forEach((value, count) -> {
                circulation.merge(value, (long) count, Long::sum);
                dirtyCoins.add(value);
            });
            return EconomyResult.done(total, change.after());
        }
    }

    /**
     * Pays cash back in. Every serial must be outstanding; one that is not — already paid in, or never
     * issued — refuses the whole lot, and nothing is credited.
     *
     * @param coins how many coins of each value; more than are in circulation refuses the lot
     * @param total what the pieces are worth together, coins included
     */
    public EconomyResult redeemCash(UUID id, List<String> serials, Map<Money, Integer> coins, Money total, Money most) {
        synchronized (lock) {
            Optional<EconomyResult> refused = refuseEarly(id, total);
            if (refused.isPresent()) {
                return refused.get();
            }
            Account account = accounts.get(id);
            for (String serial : serials) {
                if (!outstanding.containsKey(serial)) {
                    return EconomyResult.failed(Outcome.REFUSED, total, account.balance());
                }
            }
            if (new LinkedHashSet<>(serials).size() != serials.size()) {
                return EconomyResult.failed(Outcome.REFUSED, total, account.balance());
            }
            for (Map.Entry<Money, Integer> each : coins.entrySet()) {
                if (each.getValue() > circulation.getOrDefault(each.getKey(), 0L)) {
                    return EconomyResult.failed(Outcome.REFUSED, total, account.balance());
                }
            }
            BalanceChange change = rule.apply(account.balance(), total, most, account.frozen());
            if (!change.allowed()) {
                return EconomyResult.failed(change.outcome(), total, account.balance());
            }
            long now = clock.getAsLong();
            for (String serial : serials) {
                Money value = outstanding.remove(serial);
                notes.add(new NoteWrite(serial, value, null, 0, id, now));
            }
            coins.forEach((value, count) -> {
                circulation.merge(value, (long) -count, Long::sum);
                dirtyCoins.add(value);
            });
            commit(account, change.after(), total, TransactionKind.DEPOSIT, "", null);
            return EconomyResult.done(total, change.after());
        }
    }

    /**
     * Claims /daily: the streak and the payment are one change, so it cannot be claimed twice by two
     * clicks in the same tick.
     */
    public EconomyResult claimDaily(UUID id, DailyClaim claim, Money amount, Money most) {
        synchronized (lock) {
            Optional<EconomyResult> refused = refuseEarly(id, amount);
            if (refused.isPresent()) {
                return refused.get();
            }
            Account account = accounts.get(id);
            if (!claim.allowed() || account.dailyDay() >= claim.day()) {
                return EconomyResult.failed(Outcome.REFUSED, amount, account.balance());
            }
            BalanceChange change = rule.apply(account.balance(), amount, most, account.frozen());
            if (!change.allowed()) {
                return EconomyResult.failed(change.outcome(), amount, account.balance());
            }
            Account claimed = account.withDaily(claim.day(), claim.streak());
            commit(claimed, change.after(), amount, TransactionKind.DAILY, "Day " + claim.streak(), null);
            return EconomyResult.done(amount, change.after());
        }
    }

    private Optional<EconomyResult> refuseEarly(UUID id, Money amount) {
        if (!loaded) {
            return Optional.of(EconomyResult.failed(Outcome.UNAVAILABLE, amount, Money.ZERO));
        }
        Account account = accounts.get(id);
        if (account == null) {
            return Optional.of(EconomyResult.failed(Outcome.NO_ACCOUNT, amount, Money.ZERO));
        }
        if (!amount.isPositive()) {
            return Optional.of(EconomyResult.failed(Outcome.INVALID_AMOUNT, amount, account.balance()));
        }
        return Optional.empty();
    }

    private void commit(Account account, Money after, Money delta, TransactionKind kind, String reason, UUID other) {
        accounts.put(account.id(), account.withBalance(after));
        dirty.add(account.id());
        journal.add(new Transaction(clock.getAsLong(), account.id(), other, delta, after, kind, reason));
    }

    private static Money abs(Money money) {
        return money.isNegative() ? money.negate() : money;
    }

    // ---------------------------------------------------------------------------- games of chance

    /** Accounts that belong to the server rather than a player — kept off leaderboards. */
    public static boolean isSystem(UUID id) {
        return id.getMostSignificantBits() == 0L;
    }

    /**
     * One game against the house, settled at once: the stake leaves and the payout (zero for a loss)
     * arrives in the same change, so the result can never be seen without having been paid for.
     */
    public EconomyResult play(UUID id, Money stake, Money payout, String reason, Money most) {
        synchronized (lock) {
            Optional<EconomyResult> refused = refuseEarly(id, stake);
            if (refused.isPresent()) {
                return refused.get();
            }
            Account account = accounts.get(id);
            BalanceChange paying = rule.apply(account.balance(), stake.negate(), most, account.frozen());
            if (!paying.allowed()) {
                return EconomyResult.failed(paying.outcome(), stake, account.balance());
            }
            Money after = paying.after();
            if (payout.isPositive()) {
                BalanceChange winning = rule.apply(after, payout, most, false);
                if (!winning.allowed()) {
                    return EconomyResult.failed(winning.outcome(), stake, account.balance());
                }
                after = winning.after();
            }
            long now = clock.getAsLong();
            journal.add(new Transaction(now, id, null, stake.negate(), paying.after(), TransactionKind.GAMBLE, reason));
            if (payout.isPositive()) {
                journal.add(new Transaction(now, id, null, payout, after, TransactionKind.GAMBLE, reason + " — won"));
            }
            accounts.put(id, account.withBalance(after));
            dirty.add(id);
            return EconomyResult.done(stake, after);
        }
    }

    /**
     * A duel between two players, settled in one change: the loser's stake goes to the winner, less the
     * house's cut. Neither stake is held while a challenge waits, so nothing can be stranded in between.
     */
    public EconomyResult duel(UUID winner, UUID loser, Money stake, Money cut, String reason, Money most) {
        synchronized (lock) {
            Optional<EconomyResult> refused = refuseEarly(loser, stake);
            if (refused.isPresent()) {
                return refused.get();
            }
            Account losing = accounts.get(loser);
            Account winning = accounts.get(winner);
            if (winning == null) {
                return EconomyResult.failed(Outcome.NO_ACCOUNT, stake, losing.balance());
            }
            if (!winning.balance().isAtLeast(stake)) {
                return EconomyResult.failed(Outcome.NOT_ENOUGH, stake, winning.balance());
            }
            BalanceChange lost = rule.apply(losing.balance(), stake.negate(), most, losing.frozen());
            if (!lost.allowed()) {
                return EconomyResult.failed(lost.outcome(), stake, losing.balance());
            }
            Money gain = stake.minus(cut.min(stake));
            BalanceChange won = gain.isZero() ? new BalanceChange(Outcome.DONE, winning.balance())
                    : rule.apply(winning.balance(), gain, most, winning.frozen());
            if (!won.allowed()) {
                return EconomyResult.failed(won.outcome(), stake, losing.balance());
            }
            commit(losing, lost.after(), stake.negate(), TransactionKind.GAMBLE, reason, winner);
            if (!gain.isZero()) {
                commit(accounts.get(winner), won.after(), gain, TransactionKind.GAMBLE, reason, loser);
            }
            return EconomyResult.done(stake, lost.after());
        }
    }

    public long drawNumber() {
        synchronized (lock) {
            return draw;
        }
    }

    public long nextDrawAt() {
        synchronized (lock) {
            return nextDrawAt;
        }
    }

    public void scheduleDraw(long at) {
        synchronized (lock) {
            nextDrawAt = at;
            lotteryDirty = true;
        }
    }

    public Map<UUID, Integer> tickets() {
        synchronized (lock) {
            return Map.copyOf(tickets);
        }
    }

    public int ticketsOf(UUID id) {
        synchronized (lock) {
            return tickets.getOrDefault(id, 0);
        }
    }

    /** Tickets for the current draw: their price goes into the pot in the same change. */
    public EconomyResult buyTickets(UUID id, int count, Money price, Money most) {
        synchronized (lock) {
            Money cost;
            try {
                cost = price.times(Math.max(0, count));
            } catch (ArithmeticException overflow) {
                return EconomyResult.failed(Outcome.INVALID_AMOUNT, price, balance(id));
            }
            Optional<EconomyResult> refused = refuseEarly(id, cost);
            if (refused.isPresent()) {
                return refused.get();
            }
            EconomyResult paid = transfer(id, LOTTERY_POT, cost, Money.ZERO, TransactionKind.LOTTERY,
                    count + " ticket(s), draw " + draw, most.max(SYSTEM_MOST));
            if (paid.succeeded()) {
                tickets.merge(id, count, Integer::sum);
                dirtyTickets.add(id);
            }
            return paid;
        }
    }

    /**
     * Ends the current draw: the prize goes to the winner, the rest of the pot is destroyed, and the next
     * draw starts with no tickets. With no winner — nobody bought a ticket — the pot carries over.
     *
     * @return what the winner was paid; zero when nothing was drawn
     */
    public Money settleDraw(UUID winner, Money prize, long nextAt) {
        synchronized (lock) {
            Money won = Money.ZERO;
            Account pot = accounts.get(LOTTERY_POT);
            if (winner != null && pot != null && accounts.containsKey(winner)) {
                Money paid = prize.min(pot.balance());
                Money destroyed = pot.balance().minus(paid);
                if (paid.isPositive()) {
                    transfer(LOTTERY_POT, winner, paid, Money.ZERO, TransactionKind.LOTTERY, "Won draw " + draw,
                            SYSTEM_MOST);
                    won = paid;
                }
                if (destroyed.isPositive()) {
                    change(LOTTERY_POT, destroyed.negate(), TransactionKind.TAX, "Lottery cut, draw " + draw,
                            null, SYSTEM_MOST);
                }
            }
            if (winner != null || tickets.isEmpty()) {
                clearedDraw = draw;
                draw++;
                tickets.clear();
                dirtyTickets.clear();
            }
            nextDrawAt = nextAt;
            lotteryDirty = true;
            return won;
        }
    }

    // ---------------------------------------------------------------------------- hiring

    public void hire(Contract contract) {
        synchronized (lock) {
            contracts.put(contract.id(), contract);
            dirtyContracts.add(contract.id());
        }
    }

    public boolean endContract(UUID id) {
        synchronized (lock) {
            if (contracts.remove(id) == null) {
                return false;
            }
            dirtyContracts.add(id);
            return true;
        }
    }

    /** Every job somebody is in, as employer or employee, oldest first. */
    public List<Contract> contractsOf(UUID player) {
        synchronized (lock) {
            return contracts.values().stream().filter(contract -> contract.involves(player)).toList();
        }
    }

    public List<Contract> employing(UUID employer) {
        synchronized (lock) {
            return contracts.values().stream().filter(contract -> contract.employer().equals(employer)).toList();
        }
    }

    /**
     * Pays every wage that is due. Each wage and its contract's next due date are one change, so a crash
     * can neither pay a wage twice nor skip one.
     */
    public List<Payday> payroll(long now, int mostMissed, Money most) {
        synchronized (lock) {
            List<Payday> days = new ArrayList<>();
            for (Contract due : List.copyOf(contracts.values())) {
                if (due.nextAt() > now) {
                    continue;
                }
                EconomyResult paid = transfer(due.employer(), due.employee(), due.wage(), Money.ZERO,
                        TransactionKind.WAGE, due.title().isEmpty() ? "Wage" : "Wage: " + due.title(), most);
                if (paid.succeeded()) {
                    Contract next = due.paid(now);
                    contracts.put(due.id(), next);
                    days.add(new Payday(next, Payday.Kind.PAID));
                } else {
                    Contract next = due.missedAt(now);
                    if (next.missed() >= Math.max(1, mostMissed)) {
                        contracts.remove(due.id());
                        days.add(new Payday(next, Payday.Kind.ENDED));
                    } else {
                        contracts.put(due.id(), next);
                        days.add(new Payday(next, Payday.Kind.MISSED));
                    }
                }
                dirtyContracts.add(due.id());
            }
            return days;
        }
    }

    // ---------------------------------------------------------------------------- writing

    /** Whether anything is waiting to be written. */
    public boolean isDirty() {
        synchronized (lock) {
            return !dirty.isEmpty() || !journal.isEmpty() || !notes.isEmpty();
        }
    }

    /**
     * Writes everything changed since the last flush, in one transaction. Off the server's threads.
     *
     * @return how many statement lines were written; -1 when the write failed and was put back
     */
    public int flush() {
        flushing.lock();
        try {
            List<Account> changed = new ArrayList<>();
            List<Transaction> lines;
            List<NoteWrite> noteWrites;
            Map<UUID, Integer> ticketWrites = new HashMap<>();
            Map<Money, Long> coinWrites = new HashMap<>();
            Map<UUID, Contract> contractWrites = new LinkedHashMap<>();
            boolean writeLottery;
            long drawNow;
            long nextNow;
            long cleared;
            synchronized (lock) {
                if (dirty.isEmpty() && journal.isEmpty() && notes.isEmpty() && !lotteryDirty && dirtyTickets.isEmpty()
                        && dirtyCoins.isEmpty() && dirtyContracts.isEmpty()) {
                    return 0;
                }
                for (UUID id : dirtyContracts) {
                    contractWrites.put(id, contracts.get(id));
                }
                dirtyContracts.clear();
                for (Money value : dirtyCoins) {
                    coinWrites.put(value, circulation.getOrDefault(value, 0L));
                }
                dirtyCoins.clear();
                for (UUID id : dirtyTickets) {
                    ticketWrites.put(id, tickets.getOrDefault(id, 0));
                }
                writeLottery = lotteryDirty || !dirtyTickets.isEmpty();
                drawNow = draw;
                nextNow = nextDrawAt;
                cleared = clearedDraw;
                dirtyTickets.clear();
                lotteryDirty = false;
                clearedDraw = -1;
                for (UUID id : dirty) {
                    Account account = accounts.get(id);
                    if (account != null) {
                        changed.add(account);
                    }
                }
                lines = List.copyOf(journal);
                noteWrites = List.copyOf(notes);
                dirty.clear();
                journal.clear();
                notes.clear();
            }
            boolean written = database.write(connection -> {
                try (PreparedStatement upsert = connection.prepareStatement(
                        "INSERT INTO account (id, name, balance, frozen, created, daily_day, daily_streak) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT(id) DO UPDATE SET name = excluded.name, "
                                + "balance = excluded.balance, frozen = excluded.frozen, "
                                + "daily_day = excluded.daily_day, daily_streak = excluded.daily_streak")) {
                    for (Account account : changed) {
                        upsert.setString(1, account.id().toString());
                        upsert.setString(2, account.name());
                        upsert.setLong(3, account.balance().minor());
                        upsert.setInt(4, account.frozen() ? 1 : 0);
                        upsert.setLong(5, account.created());
                        upsert.setLong(6, account.dailyDay());
                        upsert.setInt(7, account.dailyStreak());
                        upsert.addBatch();
                    }
                    upsert.executeBatch();
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO ledger (at, account, other, delta, balance, kind, reason) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                    for (Transaction line : lines) {
                        insert.setLong(1, line.at());
                        insert.setString(2, line.account().toString());
                        insert.setString(3, line.other() == null ? null : line.other().toString());
                        insert.setLong(4, line.delta().minor());
                        insert.setLong(5, line.balance().minor());
                        insert.setString(6, line.kind().name());
                        insert.setString(7, line.reason());
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                try (PreparedStatement upsert = connection.prepareStatement(
                        "INSERT INTO contract (id, employer, employer_name, employee, employee_name, wage, every, next_at, "
                                + "missed, title, since) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(id) DO UPDATE "
                                + "SET next_at = excluded.next_at, missed = excluded.missed, wage = excluded.wage");
                     PreparedStatement delete = connection.prepareStatement("DELETE FROM contract WHERE id = ?")) {
                    for (Map.Entry<UUID, Contract> each : contractWrites.entrySet()) {
                        Contract job = each.getValue();
                        if (job == null) {
                            delete.setString(1, each.getKey().toString());
                            delete.executeUpdate();
                            continue;
                        }
                        upsert.setString(1, job.id().toString());
                        upsert.setString(2, job.employer().toString());
                        upsert.setString(3, job.employerName());
                        upsert.setString(4, job.employee().toString());
                        upsert.setString(5, job.employeeName());
                        upsert.setLong(6, job.wage().minor());
                        upsert.setInt(7, job.everyMinutes());
                        upsert.setLong(8, job.nextAt());
                        upsert.setInt(9, job.missed());
                        upsert.setString(10, job.title());
                        upsert.setLong(11, job.since());
                        upsert.executeUpdate();
                    }
                }
                try (PreparedStatement coins = connection.prepareStatement(
                        "INSERT INTO coin_float (value, outstanding) VALUES (?, ?) ON CONFLICT(value) "
                                + "DO UPDATE SET outstanding = excluded.outstanding")) {
                    for (Map.Entry<Money, Long> each : coinWrites.entrySet()) {
                        coins.setLong(1, each.getKey().minor());
                        coins.setLong(2, each.getValue());
                        coins.addBatch();
                    }
                    coins.executeBatch();
                }
                if (writeLottery) {
                    try (PreparedStatement state = connection.prepareStatement(
                            "INSERT INTO lottery (id, draw, next_at) VALUES (1, ?, ?) ON CONFLICT(id) "
                                    + "DO UPDATE SET draw = excluded.draw, next_at = excluded.next_at")) {
                        state.setLong(1, drawNow);
                        state.setLong(2, nextNow);
                        state.executeUpdate();
                    }
                    if (cleared >= 0) {
                        try (PreparedStatement clear = connection.prepareStatement(
                                "DELETE FROM lottery_ticket WHERE draw <= ?")) {
                            clear.setLong(1, cleared);
                            clear.executeUpdate();
                        }
                    }
                    try (PreparedStatement held = connection.prepareStatement(
                            "INSERT INTO lottery_ticket (draw, player, tickets) VALUES (?, ?, ?) "
                                    + "ON CONFLICT(draw, player) DO UPDATE SET tickets = excluded.tickets")) {
                        for (Map.Entry<UUID, Integer> each : ticketWrites.entrySet()) {
                            held.setLong(1, drawNow);
                            held.setString(2, each.getKey().toString());
                            held.setInt(3, each.getValue());
                            held.addBatch();
                        }
                        held.executeBatch();
                    }
                }
                try (PreparedStatement issue = connection.prepareStatement(
                        "INSERT OR IGNORE INTO note (serial, value, issued_to, issued_at) VALUES (?, ?, ?, ?)");
                     PreparedStatement redeem = connection.prepareStatement(
                             "UPDATE note SET redeemed_by = ?, redeemed_at = ? WHERE serial = ?")) {
                    for (NoteWrite note : noteWrites) {
                        if (note.redeemedBy() == null) {
                            issue.setString(1, note.serial());
                            issue.setLong(2, note.value().minor());
                            issue.setString(3, note.issuedTo().toString());
                            issue.setLong(4, note.issuedAt());
                            issue.executeUpdate();
                        } else {
                            redeem.setString(1, note.redeemedBy().toString());
                            redeem.setLong(2, note.redeemedAt());
                            redeem.setString(3, note.serial());
                            redeem.executeUpdate();
                        }
                    }
                }
            });
            if (!written) {
                synchronized (lock) {
                    changed.forEach(account -> dirty.add(account.id()));
                    journal.addAll(0, lines);
                    notes.addAll(0, noteWrites);
                    dirtyTickets.addAll(ticketWrites.keySet());
                    dirtyCoins.addAll(coinWrites.keySet());
                    dirtyContracts.addAll(contractWrites.keySet());
                    lotteryDirty |= writeLottery;
                    if (cleared >= 0 && clearedDraw < 0) {
                        clearedDraw = cleared;
                    }
                }
                return -1;
            }
            return lines.size();
        } finally {
            flushing.unlock();
        }
    }

    /** One account's statement, newest first. Flushes first, so it is complete. Off the server's threads. */
    public List<Transaction> history(UUID id, int limit, int offset) {
        flush();
        return database.read(connection -> {
            List<Transaction> lines = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT at, other, delta, balance, kind, reason FROM ledger WHERE account = ? "
                            + "ORDER BY id DESC LIMIT ? OFFSET ?")) {
                select.setString(1, id.toString());
                select.setInt(2, Math.max(1, limit));
                select.setInt(3, Math.max(0, offset));
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        String other = rows.getString(2);
                        lines.add(new Transaction(rows.getLong(1), id, other == null ? null : UUID.fromString(other),
                                Money.of(rows.getLong(3)), Money.of(rows.getLong(4)),
                                TransactionKind.read(rows.getString(5)), rows.getString(6)));
                    }
                }
            }
            return lines;
        }).orElse(List.of());
    }

    /** What arrived since a moment, by kind — "while you were away". Off the server's threads. */
    public Money arrivedSince(UUID id, long since, Collection<TransactionKind> kinds) {
        flush();
        return database.read(connection -> {
            long total = 0;
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT delta, kind FROM ledger WHERE account = ? AND at > ? AND delta > 0")) {
                select.setString(1, id.toString());
                select.setLong(2, since);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        if (kinds.contains(TransactionKind.read(rows.getString(2)))) {
                            total = Math.addExact(total, rows.getLong(1));
                        }
                    }
                }
            }
            return Money.of(total);
        }).orElse(Money.ZERO);
    }

    /** Deletes statement lines older than a moment. Off the server's threads. */
    public int forgetHistoryBefore(long cutoff) {
        flush();
        int[] deleted = {0};
        database.write(connection -> {
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM ledger WHERE at < ?")) {
                delete.setLong(1, cutoff);
                deleted[0] = delete.executeUpdate();
            }
        });
        return deleted[0];
    }
}
