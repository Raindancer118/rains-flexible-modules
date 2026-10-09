package de.raindancer.modules.economy.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.EconomyResult.Outcome;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.Account;
import de.raindancer.modules.economy.model.Auction;
import de.raindancer.modules.economy.model.AuctionBid;
import de.raindancer.modules.economy.model.AuctionClaim;
import de.raindancer.modules.economy.model.AuctionEnd;
import de.raindancer.modules.economy.model.Raffle;
import de.raindancer.modules.economy.model.RaffleBuy;
import de.raindancer.modules.economy.model.RaffleDraw;
import de.raindancer.modules.economy.model.TaxRun;
import de.raindancer.modules.economy.rules.RaffleRule;
import de.raindancer.modules.economy.model.BalanceChange;
import de.raindancer.modules.economy.model.Contract;
import de.raindancer.modules.economy.model.CreditHistory;
import de.raindancer.modules.economy.model.Loan;
import de.raindancer.modules.economy.model.LoanCollection;
import de.raindancer.modules.economy.model.LotteryTicket;
import de.raindancer.modules.economy.model.Payday;
import de.raindancer.modules.economy.model.DailyClaim;
import de.raindancer.modules.economy.model.Transaction;
import de.raindancer.modules.economy.model.TransactionKind;
import de.raindancer.modules.economy.rules.BalanceRule;
import de.raindancer.modules.economy.rules.LoanRule;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.LongUnaryOperator;

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
    private static final LoanRule LOANS = new LoanRule();
    private final Map<UUID, Loan> loans = new LinkedHashMap<>();
    private final Set<UUID> dirtyLoans = new LinkedHashSet<>();
    private final Set<UUID> dirty = new LinkedHashSet<>();
    private final List<Transaction> journal = new ArrayList<>();
    /** Lifetime totals as written; lines still in {@link #journal} or being flushed are added when read. */
    private final Map<UUID, CreditHistory> credit = new HashMap<>();
    /** Lines taken out of the journal by a flush that has not finished, so reading in between misses nothing. */
    private List<Transaction> flushingLines = List.of();
    private final Set<UUID> dirtyCredit = new LinkedHashSet<>();
    /** Minutes played (Core's count of minutes not away); the hour a line lands in is this divided by sixty. */
    private volatile java.util.function.ToLongFunction<UUID> playMinutes = id -> 0L;
    /** Per account, per hour of playtime: earned, staked, won. Only the last {@link #HOURS_KEPT} are kept. */
    private final Map<UUID, java.util.TreeMap<Long, CreditHistory.Recent>> hours = new HashMap<>();
    /** A week of play: more than any sensible "lately", few enough rows to rewrite whole. */
    public static final int HOURS_KEPT = 168;
    private final List<NoteWrite> notes = new ArrayList<>();
    private volatile boolean loaded;

    /** The lottery's pot is an account of its own, so tickets bought and the pot are one ledger. */
    public static final UUID LOTTERY_POT = new UUID(0L, 0x1077E7L);
    /** Where bids wait while their auction runs. */
    public static final UUID AUCTION_ESCROW = new UUID(0L, 0xA0C710L);
    private final Map<UUID, Auction> auctions = new LinkedHashMap<>();
    private final Set<UUID> dirtyAuctions = new LinkedHashSet<>();
    private final Map<UUID, AuctionClaim> claims = new LinkedHashMap<>();
    private final Set<UUID> dirtyClaims = new LinkedHashSet<>();
    /** Where raffle tickets' money waits for the draw. */
    public static final UUID RAFFLE_POT = new UUID(0L, 0x4AFF1EL);
    private static final RaffleRule RAFFLES = new RaffleRule();
    private final Map<UUID, Raffle> raffles = new LinkedHashMap<>();
    private final Set<UUID> dirtyRaffles = new LinkedHashSet<>();
    private int nextRaffle = 1;
    private boolean raffleCounterDirty;
    private long lastWealthTax;
    private boolean wealthTaxDirty;
    private static final Money SYSTEM_MOST = Money.of(Long.MAX_VALUE / 4);
    /** Scratch tickets share the cheque register; this keeps a ticket from ever passing for a cheque. */
    private static final String TICKET = "ticket:";
    private long draw = 1;
    private long nextDrawAt;
    private final List<LotteryTicket> tickets = new ArrayList<>();
    private final List<LotteryTicket> newTickets = new ArrayList<>();
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
                            + "since, kind FROM contract ORDER BY since");
                 ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    jobs.add(new Contract(UUID.fromString(rows.getString(1)), UUID.fromString(rows.getString(2)),
                            rows.getString(3), UUID.fromString(rows.getString(4)), rows.getString(5),
                            Money.of(rows.getLong(6)), rows.getInt(7), rows.getLong(8), rows.getInt(9),
                            rows.getString(10), rows.getLong(11),
                            "SERVICE".equals(rows.getString(12)) ? Contract.Kind.SERVICE : Contract.Kind.JOB));
                }
            }
            List<Loan> lent = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT player, name, borrowed, owed, taken_at, due_at, late_at FROM loan ORDER BY taken_at");
                 ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    lent.add(new Loan(UUID.fromString(rows.getString(1)), rows.getString(2), Money.of(rows.getLong(3)),
                            Money.of(rows.getLong(4)), rows.getLong(5), rows.getLong(6), rows.getLong(7)));
                }
            }
            Map<UUID, Map<TransactionKind, Money[]>> totals = new HashMap<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT account, kind, gained, lost FROM ledger_total");
                 ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    totals.computeIfAbsent(UUID.fromString(rows.getString(1)), id -> new EnumMap<>(TransactionKind.class))
                            .merge(TransactionKind.read(rows.getString(2)),
                                    new Money[]{Money.of(rows.getLong(3)), Money.of(rows.getLong(4))},
                                    (a, b) -> new Money[]{a[0].plus(b[0]), a[1].plus(b[1])});
                }
            }
            Map<UUID, int[]> records = new HashMap<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT account, on_time, late FROM credit_record");
                 ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    records.put(UUID.fromString(rows.getString(1)), new int[]{rows.getInt(2), rows.getInt(3)});
                }
            }
            Map<UUID, java.util.TreeMap<Long, CreditHistory.Recent>> hoursRead = new HashMap<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT account, hour, earned, spent, staked, won, received, paid FROM credit_hour");
                 ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    hoursRead.computeIfAbsent(UUID.fromString(rows.getString(1)), id -> new java.util.TreeMap<>())
                            .put(rows.getLong(2), new CreditHistory.Recent(Money.of(rows.getLong(3)),
                                    Money.of(rows.getLong(4)), Money.of(rows.getLong(5)), Money.of(rows.getLong(6)),
                                    Money.of(rows.getLong(7)), Money.of(rows.getLong(8))));
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
            List<LotteryTicket> held = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT player, numbers FROM lottery_pick WHERE draw = ? ORDER BY id")) {
                select.setLong(1, drawRead);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        held.add(new LotteryTicket(UUID.fromString(rows.getString(1)),
                                LotteryTicket.read(rows.getString(2))));
                    }
                }
            }
            List<Auction> listed = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT id, seller, seller_name, item, item_name, start, buyout, bid, bidder, bidder_name, bids, "
                            + "seconds, listed_at, ends_at FROM auction ORDER BY listed_at, rowid");
                 ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    String bidder = rows.getString(9);
                    listed.add(new Auction(UUID.fromString(rows.getString(1)), UUID.fromString(rows.getString(2)),
                            rows.getString(3), rows.getBytes(4), rows.getString(5), Money.of(rows.getLong(6)),
                            Money.of(rows.getLong(7)), Money.of(rows.getLong(8)),
                            bidder == null ? null : UUID.fromString(bidder), rows.getString(10), rows.getInt(11),
                            rows.getInt(12), rows.getLong(13), rows.getLong(14)));
                }
            }
            List<AuctionClaim> owed = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT id, player, item, item_name, reason, at FROM auction_claim ORDER BY at, rowid");
                 ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    owed.add(new AuctionClaim(UUID.fromString(rows.getString(1)), UUID.fromString(rows.getString(2)),
                            rows.getBytes(3), rows.getString(4), AuctionClaim.Reason.valueOf(rows.getString(5)),
                            rows.getLong(6)));
                }
            }
            Map<UUID, Map<UUID, Integer>> raffleTickets = new HashMap<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT raffle, player, tickets FROM raffle_ticket ORDER BY raffle, seq");
                 ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    raffleTickets.computeIfAbsent(UUID.fromString(rows.getString(1)), id -> new LinkedHashMap<>())
                            .put(UUID.fromString(rows.getString(2)), rows.getInt(3));
                }
            }
            List<Raffle> running = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT id, number, host, host_name, item, prize_name, prize, ticket_price, most_tickets, per_player, "
                            + "started_at, ends_at FROM raffle ORDER BY number");
                 ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    UUID id = UUID.fromString(rows.getString(1));
                    String hostId = rows.getString(3);
                    running.add(new Raffle(id, rows.getInt(2), hostId == null ? null : UUID.fromString(hostId),
                            rows.getString(4), rows.getBytes(5), rows.getString(6), Money.of(rows.getLong(7)),
                            Money.of(rows.getLong(8)), rows.getInt(9), rows.getInt(10), rows.getLong(11),
                            rows.getLong(12), raffleTickets.getOrDefault(id, Map.of())));
                }
            }
            int counter = 1;
            try (PreparedStatement select = connection.prepareStatement("SELECT next FROM raffle_counter WHERE id = 1");
                 ResultSet rows = select.executeQuery()) {
                if (rows.next()) {
                    counter = rows.getInt(1);
                }
            }
            int counted = counter;
            long taxedAt = 0;
            try (PreparedStatement select = connection.prepareStatement("SELECT last_at FROM wealth_tax WHERE id = 1");
                 ResultSet rows = select.executeQuery()) {
                if (rows.next()) {
                    taxedAt = rows.getLong(1);
                }
            }
            long lastTaxed = taxedAt;
            synchronized (lock) {
                lastWealthTax = lastTaxed;
                running.forEach(raffle -> raffles.put(raffle.id(), raffle));
                nextRaffle = Math.max(counted, running.stream().mapToInt(Raffle::number).max().orElse(0) + 1);
                listed.forEach(auction -> auctions.put(auction.id(), auction));
                owed.forEach(claim -> claims.put(claim.id(), claim));
                accounts.putAll(found);
                outstanding.putAll(open);
                circulation.putAll(coinsOut);
                jobs.forEach(job -> contracts.put(job.id(), job));
                lent.forEach(loan -> loans.put(loan.player(), loan));
                java.util.Set<UUID> known = new java.util.HashSet<>(totals.keySet());
                known.addAll(records.keySet());
                for (UUID id : known) {
                    Map<TransactionKind, Money> in = new EnumMap<>(TransactionKind.class);
                    Map<TransactionKind, Money> out = new EnumMap<>(TransactionKind.class);
                    totals.getOrDefault(id, Map.of()).forEach((kind, pair) -> {
                        in.put(kind, pair[0]);
                        out.put(kind, pair[1]);
                    });
                    int[] record = records.getOrDefault(id, new int[2]);
                    credit.put(id, new CreditHistory(in, out, record[0], record[1], CreditHistory.Recent.NONE));
                }
                hours.putAll(hoursRead);
                draw = drawRead;
                nextDrawAt = nextRead;
                tickets.addAll(held);
            }
            return true;
        });
        loaded = read.orElse(false);
        if (loaded) {
            open(LOTTERY_POT, "Lottery pot", Money.ZERO);
            open(AUCTION_ESCROW, "Auction escrow", Money.ZERO);
            open(RAFFLE_POT, "Raffle pot", Money.ZERO);
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

    public List<LotteryTicket> tickets() {
        synchronized (lock) {
            return List.copyOf(tickets);
        }
    }

    public List<LotteryTicket> ticketsOf(UUID id) {
        synchronized (lock) {
            return tickets.stream().filter(ticket -> ticket.player().equals(id)).toList();
        }
    }

    /**
     * Tickets for the current draw: their price goes into the pot in the same change, less the cut, which
     * is destroyed.
     */
    public EconomyResult buyTickets(UUID id, List<List<Integer>> picks, Money price, Money cut, Money most) {
        synchronized (lock) {
            Money cost;
            Money cuts;
            try {
                cost = price.times(picks.size());
                cuts = cut.times(picks.size());
            } catch (ArithmeticException overflow) {
                return EconomyResult.failed(Outcome.INVALID_AMOUNT, price, balance(id));
            }
            Optional<EconomyResult> refused = refuseEarly(id, cost);
            if (refused.isPresent()) {
                return refused.get();
            }
            EconomyResult paid = transfer(id, LOTTERY_POT, cost, Money.ZERO, TransactionKind.LOTTERY,
                    picks.size() + " ticket(s), draw " + draw, most.max(SYSTEM_MOST));
            if (paid.succeeded()) {
                if (cuts.isPositive()) {
                    change(LOTTERY_POT, cuts.negate(), TransactionKind.TAX, "Lottery cut", null, SYSTEM_MOST);
                }
                for (List<Integer> numbers : picks) {
                    LotteryTicket ticket = new LotteryTicket(id, numbers);
                    tickets.add(ticket);
                    newTickets.add(ticket);
                }
            }
            return paid;
        }
    }

    /**
     * Ends the current draw: every prize is paid out of the pot, what nobody won stays in it, and the next
     * draw starts with no tickets.
     *
     * @return what was paid in all
     */
    public Money settleDraw(Map<UUID, Money> prizes, long nextAt) {
        synchronized (lock) {
            long paid = 0;
            for (Map.Entry<UUID, Money> each : prizes.entrySet()) {
                Money left = balance(LOTTERY_POT);
                Money prize = each.getValue().min(left);
                if (prize.isPositive() && accounts.containsKey(each.getKey())) {
                    EconomyResult result = transfer(LOTTERY_POT, each.getKey(), prize, Money.ZERO,
                            TransactionKind.LOTTERY, "Won draw " + draw, SYSTEM_MOST);
                    if (result.succeeded()) {
                        paid += prize.minor();
                    }
                }
            }
            clearedDraw = draw;
            draw++;
            tickets.clear();
            newTickets.clear();
            nextDrawAt = nextAt;
            lotteryDirty = true;
            return Money.of(paid);
        }
    }

    // ---------------------------------------------------------------------------- scratch tickets

    /** Buys a scratch ticket: the price leaves and the ticket's serial is registered, as one change. */
    public EconomyResult buyTicket(UUID id, String serial, Money price, Money most) {
        synchronized (lock) {
            EconomyResult paid = play(id, price, Money.ZERO, "Scratch card", most);
            if (paid.succeeded()) {
                outstanding.put(TICKET + serial, price);
                notes.add(new NoteWrite(TICKET + serial, price, id, clock.getAsLong(), null, 0));
            }
            return paid;
        }
    }

    /** Uses a ticket up; false when it was never sold or is already scratched — a copy. */
    public boolean useTicket(UUID by, String serial) {
        synchronized (lock) {
            Money price = outstanding.remove(TICKET + serial);
            if (price == null) {
                return false;
            }
            notes.add(new NoteWrite(TICKET + serial, price, null, 0, by, clock.getAsLong()));
            return true;
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
                String word = due.isService() ? "Contract" : "Wage";
                EconomyResult paid = transfer(due.employer(), due.employee(), due.wage(), Money.ZERO,
                        due.isService() ? TransactionKind.CONTRACT : TransactionKind.WAGE,
                        due.title().isEmpty() ? word : word + ": " + due.title(), most);
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

    // ---------------------------------------------------------------------------- loans

    /** Pays a loan out and opens it, both or neither; refused while the player already owes the bank. */
    public EconomyResult borrow(Loan loan, Money most) {
        synchronized (lock) {
            if (loans.containsKey(loan.player())) {
                return EconomyResult.failed(Outcome.REFUSED, loan.borrowed(), balance(loan.player()));
            }
            EconomyResult paid = change(loan.player(), loan.borrowed(), TransactionKind.LOAN, "Loan", null, most);
            if (paid.succeeded()) {
                loans.put(loan.player(), loan);
                dirtyLoans.add(loan.player());
            }
            return paid;
        }
    }

    /** Pays back up to {@code amount} — never more than is owed; closes the loan when nothing is left. */
    public EconomyResult repay(UUID player, Money amount) {
        synchronized (lock) {
            Loan loan = loans.get(player);
            if (loan == null || !amount.isPositive()) {
                return EconomyResult.failed(Outcome.REFUSED, amount, balance(player));
            }
            Money paying = amount.min(loan.owed());
            EconomyResult paid = change(player, paying.negate(), TransactionKind.LOAN, "Loan paid back", null,
                    SYSTEM_MOST);
            if (paid.succeeded()) {
                settle(loan.paid(paying));
            }
            return paid;
        }
    }

    /**
     * Every overdue loan: late fees for each new whole day, then whatever the balance holds taken toward it.
     * Only loans where something happened are returned.
     */
    public List<LoanCollection> collectLoans(long now, double latePerDay, Money most) {
        synchronized (lock) {
            List<LoanCollection> done = new ArrayList<>();
            for (Loan due : List.copyOf(loans.values())) {
                if (!due.overdue(now)) {
                    continue;
                }
                Loan fined = LOANS.withLateFees(due, now, latePerDay);
                Money fee = fined.owed().minus(due.owed());
                Money take = LOANS.collect(balance(due.player()), fined.owed());
                Loan after = fined;
                if (take.isPositive() && change(due.player(), take.negate(), TransactionKind.LOAN,
                        "Overdue loan collected", null, most).succeeded()) {
                    after = fined.paid(take);
                } else {
                    take = Money.ZERO;
                }
                if (!after.equals(due)) {
                    settle(after);
                }
                if (fee.isPositive() || take.isPositive()) {
                    done.add(new LoanCollection(after, fee, take, after.settled()));
                }
            }
            return done;
        }
    }

    private void settle(Loan loan) {
        if (loan.settled()) {
            boolean onTime = clock.getAsLong() <= loan.dueAt();
            credit.merge(loan.player(), CreditHistory.EMPTY.withLoanEnded(onTime),
                    (had, added) -> had.withLoanEnded(onTime));
            dirtyCredit.add(loan.player());
            loans.remove(loan.player());
        } else {
            loans.put(loan.player(), loan);
        }
        dirtyLoans.add(loan.player());
    }

    /** Where minutes played come from — Core's playtime, wired in by the module. Zero for everybody until then. */
    public void playtime(java.util.function.ToLongFunction<UUID> minutes) {
        this.playMinutes = minutes == null ? id -> 0L : minutes;
    }

    /** The same as {@link #credit(UUID)}, with what happened in the last {@code hoursOfPlay} hours of playtime. */
    public CreditHistory credit(UUID id, int hoursOfPlay) {
        synchronized (lock) {
            long now = playMinutes.applyAsLong(id) / 60;
            CreditHistory.Recent recent = CreditHistory.Recent.NONE;
            for (CreditHistory.Recent hour : hours.getOrDefault(id, new java.util.TreeMap<>())
                    .tailMap(now - hoursOfPlay, false).values()) {
                recent = recent.plus(hour);
            }
            for (Transaction line : flushingLines) {
                if (line.account().equals(id)) {
                    recent = recent.plus(lately(line));
                }
            }
            for (Transaction line : journal) {
                if (line.account().equals(id)) {
                    recent = recent.plus(lately(line));
                }
            }
            return credit(id).withRecent(hoursOfPlay > 0 ? recent : CreditHistory.Recent.NONE);
        }
    }

    /** What one line adds to an hour of play. */
    private static CreditHistory.Recent lately(Transaction line) {
        long delta = line.delta().minor();
        Money in = Money.of(Math.max(0, delta));
        Money out = Money.of(Math.max(0, -delta));
        Money none = Money.ZERO;
        TransactionKind kind = line.kind();
        if (de.raindancer.modules.economy.rules.CreditRule.GAMBLING.contains(kind)) {
            return new CreditHistory.Recent(none, none, out, in, none, none);
        }
        if (de.raindancer.modules.economy.rules.CreditRule.EARNING.contains(kind)) {
            return new CreditHistory.Recent(in, none, none, none, none, none);
        }
        if (de.raindancer.modules.economy.rules.CreditRule.SPENDING.contains(kind)) {
            return new CreditHistory.Recent(none, out, none, none, none, none);
        }
        if (de.raindancer.modules.economy.rules.CreditRule.BETWEEN_PLAYERS.contains(kind)) {
            return new CreditHistory.Recent(none, none, none, none, in, out);
        }
        return CreditHistory.Recent.NONE;
    }

    /** Everything this account ever took in and paid out, and how its loans ended — lines not yet written included. */
    public CreditHistory credit(UUID id) {
        synchronized (lock) {
            CreditHistory history = credit.getOrDefault(id, CreditHistory.EMPTY);
            for (Transaction line : flushingLines) {
                if (line.account().equals(id)) {
                    history = history.with(line);
                }
            }
            for (Transaction line : journal) {
                if (line.account().equals(id)) {
                    history = history.with(line);
                }
            }
            return history;
        }
    }

    public Optional<Loan> loanOf(UUID player) {
        synchronized (lock) {
            return Optional.ofNullable(loans.get(player));
        }
    }

    /** Every open loan, oldest first. */
    public List<Loan> loans() {
        synchronized (lock) {
            return List.copyOf(loans.values());
        }
    }

    /** Staff wipe a loan: nothing more is owed, nothing is paid back. */
    public boolean forgive(UUID player) {
        synchronized (lock) {
            if (loans.remove(player) == null) {
                return false;
            }
            dirtyLoans.add(player);
            return true;
        }
    }

    // ---------------------------------------------------------------------------- auctions

    /**
     * Queues an auction, taking the listing fee (which leaves the economy) in the same change.
     *
     * @param queueSize how many may wait at most — checked here, under the lock, so two listings at once
     *                  cannot both squeeze into the last place
     * @param perPlayer how many one seller may have running or waiting
     */
    public EconomyResult listAuction(Auction auction, Money fee, Money most, int queueSize, int perPlayer) {
        synchronized (lock) {
            long waiting = auctions.values().stream().filter(listed -> !listed.live()).count();
            long mine = auctions.values().stream().filter(listed -> listed.seller().equals(auction.seller())).count();
            if (waiting >= queueSize || mine >= perPlayer) {
                return EconomyResult.failed(Outcome.REFUSED, fee, balance(auction.seller()));
            }
            if (fee.isPositive()) {
                EconomyResult paid = change(auction.seller(), fee.negate(), TransactionKind.AUCTION,
                        "Listing fee: " + auction.itemName(), null, most);
                if (!paid.succeeded()) {
                    return paid;
                }
                remember(auction);
                return paid;
            }
            if (!loaded || !accounts.containsKey(auction.seller())) {
                return EconomyResult.failed(loaded ? Outcome.NO_ACCOUNT : Outcome.UNAVAILABLE, fee, Money.ZERO);
            }
            Account seller = accounts.get(auction.seller());
            if (seller.frozen()) {
                return EconomyResult.failed(Outcome.FROZEN, fee, seller.balance());
            }
            remember(auction);
            return EconomyResult.done(Money.ZERO, accounts.get(auction.seller()).balance());
        }
    }

    private void remember(Auction auction) {
        auctions.put(auction.id(), auction);
        dirtyAuctions.add(auction.id());
    }

    /** The live auction first, then the queue in the order it was listed. */
    public List<Auction> auctions() {
        synchronized (lock) {
            List<Auction> all = new ArrayList<>(auctions.values());
            all.sort((a, b) -> Boolean.compare(b.live(), a.live()));
            return all;
        }
    }

    public Optional<Auction> auction(UUID id) {
        synchronized (lock) {
            return Optional.ofNullable(auctions.get(id));
        }
    }

    public Optional<Auction> liveAuction() {
        synchronized (lock) {
            return auctions.values().stream().filter(Auction::live).findFirst();
        }
    }

    /** Starts the oldest queued auction, unless one is already running. */
    public Optional<Auction> startNextAuction(long now) {
        synchronized (lock) {
            if (auctions.values().stream().anyMatch(Auction::live)) {
                return Optional.empty();
            }
            Optional<Auction> next = auctions.values().stream().findFirst().map(auction -> auction.started(now));
            next.ifPresent(this::remember);
            return next;
        }
    }

    public void extendAuction(UUID id, long endsAt) {
        synchronized (lock) {
            Auction auction = auctions.get(id);
            if (auction != null && auction.live()) {
                remember(auction.endingAt(endsAt));
            }
        }
    }

    /**
     * A bid: the money goes into escrow and the bidder it beats is paid back, in one change.
     *
     * @param minimum the smallest bid accepted, asked of the auction as it is at this moment
     * @param end     the auction's end after this bid, from its end before
     * @param now     a bid at or after the end is too late — decided here, under the lock, not by a caller's picture
     */
    public AuctionBid bid(UUID auctionId, UUID bidder, String name, Money amount, Function<Auction, Money> minimum,
                          LongUnaryOperator end, long now, Money most) {
        synchronized (lock) {
            Auction auction = auctions.get(auctionId);
            if (auction == null || !auction.live() || auction.endsAt() <= now) {
                return new AuctionBid(AuctionBid.Kind.GONE, auction, null, Money.ZERO, null);
            }
            if (auction.seller().equals(bidder)) {
                return new AuctionBid(AuctionBid.Kind.OWN, auction, null, Money.ZERO, null);
            }
            if (bidder.equals(auction.bidder())) {
                return new AuctionBid(AuctionBid.Kind.TOP, auction, null, Money.ZERO, null);
            }
            if (!amount.isAtLeast(minimum.apply(auction))) {
                return new AuctionBid(AuctionBid.Kind.TOO_LOW, auction, null, Money.ZERO, null);
            }
            Optional<EconomyResult> refused = refuseEarly(bidder, amount);
            if (refused.isPresent()) {
                return new AuctionBid(AuctionBid.Kind.REFUSED, auction, null, Money.ZERO, refused.get());
            }
            Account account = accounts.get(bidder);
            BalanceChange leaving = rule.apply(account.balance(), amount.negate(), most, account.frozen());
            if (!leaving.allowed()) {
                return new AuctionBid(AuctionBid.Kind.REFUSED, auction, null, Money.ZERO,
                        EconomyResult.failed(leaving.outcome(), amount, account.balance()));
            }
            commit(account, leaving.after(), amount.negate(), TransactionKind.AUCTION, "Bid: " + auction.itemName(),
                    AUCTION_ESCROW);
            credit(AUCTION_ESCROW, amount, TransactionKind.AUCTION, "Bid by " + name + ": " + auction.itemName(), bidder);
            UUID outbid = auction.bidder();
            Money refunded = Money.ZERO;
            if (outbid != null) {
                release(outbid, auction.bid(), "Outbid: " + auction.itemName());
                refunded = auction.bid();
            }
            Auction after = auction.withBid(bidder, name, amount, end.applyAsLong(auction.endsAt()));
            remember(after);
            return new AuctionBid(AuctionBid.Kind.PLACED, after, outbid, refunded,
                    EconomyResult.done(amount, leaving.after()));
        }
    }

    /**
     * Ends an auction: sold, the seller is paid the bid less the fee (which leaves the economy) and the item
     * is owed to the winner; unsold, the item is owed back to the seller.
     */
    public Optional<AuctionEnd> endAuction(UUID id, Function<Money, Money> fee, long now) {
        synchronized (lock) {
            Auction auction = auctions.get(id);
            // A bid may have moved the end since the caller looked.
            if (auction == null || !auction.live() || auction.endsAt() > now) {
                return Optional.empty();
            }
            auctions.remove(id);
            dirtyAuctions.add(id);
            if (!auction.hasBid()) {
                return Optional.of(new AuctionEnd(auction, owe(auction, auction.seller(), AuctionClaim.Reason.UNSOLD),
                        false, Money.ZERO, Money.ZERO));
            }
            Money kept = fee.apply(auction.bid()).min(auction.bid()).max(Money.ZERO);
            Money paid = auction.bid().minus(kept);
            if (paid.isPositive()) {
                release(auction.seller(), paid, "Sold: " + auction.itemName());
            }
            if (kept.isPositive()) {
                destroy(AUCTION_ESCROW, kept, "Auction fee: " + auction.itemName(), auction.seller());
            }
            return Optional.of(new AuctionEnd(auction, owe(auction, auction.bidder(), AuctionClaim.Reason.WON),
                    true, paid, kept));
        }
    }

    /** Takes an auction off, queued or live: any bid goes back and the item is owed to the seller. */
    public Optional<AuctionEnd> cancelAuction(UUID id) {
        synchronized (lock) {
            Auction auction = auctions.remove(id);
            if (auction == null) {
                return Optional.empty();
            }
            dirtyAuctions.add(id);
            if (auction.hasBid()) {
                release(auction.bidder(), auction.bid(), "Auction called off: " + auction.itemName());
            }
            return Optional.of(new AuctionEnd(auction, owe(auction, auction.seller(), AuctionClaim.Reason.CANCELLED),
                    false, Money.ZERO, Money.ZERO));
        }
    }

    private AuctionClaim owe(Auction auction, UUID to, AuctionClaim.Reason reason) {
        return owe(auction.item(), auction.itemName(), to, reason);
    }

    private AuctionClaim owe(byte[] item, String name, UUID to, AuctionClaim.Reason reason) {
        AuctionClaim claim = new AuctionClaim(UUID.randomUUID(), to, item, name, reason, clock.getAsLong());
        claims.put(claim.id(), claim);
        dirtyClaims.add(claim.id());
        return claim;
    }

    public List<AuctionClaim> claimsOf(UUID player) {
        synchronized (lock) {
            return claims.values().stream().filter(claim -> claim.player().equals(player)).toList();
        }
    }

    /** Marks an owed item as handed over. Whoever gets {@code true} hands it over; only one caller ever does. */
    public boolean takeClaim(UUID id) {
        synchronized (lock) {
            if (claims.remove(id) == null) {
                return false;
            }
            dirtyClaims.add(id);
            return true;
        }
    }

    // Escrow moves skip the balance rule: the money already belonged to whoever it goes back to, so neither a
    // freeze nor the balance cap may strand it.
    private void credit(UUID to, Money amount, TransactionKind kind, String reason, UUID other) {
        Account account = accounts.get(to);
        if (account == null) {
            account = new Account(to, "", Money.ZERO, false, clock.getAsLong(), -1, 0);
        }
        commit(account, account.balance().plus(amount), amount, kind, reason, other);
    }

    private void release(UUID to, Money amount, String reason) {
        release(AUCTION_ESCROW, to, amount, TransactionKind.AUCTION, reason);
    }

    private void release(UUID from, UUID to, Money amount, TransactionKind kind, String reason) {
        Account held = accounts.get(from);
        commit(held, held.balance().minus(amount), amount.negate(), kind, reason, to);
        credit(to, amount, kind, reason, from);
    }

    /** Money a system account holds that leaves the economy — a fee, a server raffle's tickets. */
    private void destroy(UUID from, Money amount, String reason, UUID other) {
        Account held = accounts.get(from);
        commit(held, held.balance().minus(amount), amount.negate(), TransactionKind.FEE, reason, other);
    }

    // ---------------------------------------------------------------------------- raffles

    /** The number the next raffle gets. Never handed out twice, also across restarts. */
    public int nextRaffleNumber() {
        synchronized (lock) {
            return nextRaffle;
        }
    }

    /**
     * Starts a raffle; a player hosting it pays the fee (which leaves the economy) in the same change.
     *
     * @param mostRunning how many may run at once, checked here under the lock
     * @param perHost     how many one player may host at once
     */
    public EconomyResult startRaffle(Raffle raffle, Money fee, Money most, int mostRunning, int perHost) {
        synchronized (lock) {
            if (!loaded) {
                return EconomyResult.failed(Outcome.UNAVAILABLE, fee, Money.ZERO);
            }
            long hosting = raffle.host() == null ? 0
                    : raffles.values().stream().filter(running -> raffle.host().equals(running.host())).count();
            if (raffles.size() >= mostRunning || hosting >= perHost) {
                return EconomyResult.failed(Outcome.REFUSED, fee, raffle.host() == null ? Money.ZERO
                        : balance(raffle.host()));
            }
            EconomyResult result = EconomyResult.done(Money.ZERO, Money.ZERO);
            if (raffle.host() != null) {
                Account host = accounts.get(raffle.host());
                if (host == null) {
                    return EconomyResult.failed(Outcome.NO_ACCOUNT, fee, Money.ZERO);
                }
                if (host.frozen()) {
                    return EconomyResult.failed(Outcome.FROZEN, fee, host.balance());
                }
                Money staked = raffle.moneyPrize() ? raffle.prize() : Money.ZERO;
                Money taken = fee.max(Money.ZERO).plus(staked);
                result = EconomyResult.done(taken, host.balance());
                if (taken.isPositive()) {
                    // Fee and prize together or neither: a host who can pay one but not both starts nothing.
                    BalanceChange leaving = rule.apply(host.balance(), taken.negate(), most, false);
                    if (!leaving.allowed()) {
                        return EconomyResult.failed(leaving.outcome(), taken, host.balance());
                    }
                    if (fee.isPositive()) {
                        commit(host, host.balance().minus(fee), fee.negate(), TransactionKind.RAFFLE,
                                "Raffle fee: #" + raffle.number(), null);
                    }
                    if (staked.isPositive()) {
                        Account paying = accounts.get(raffle.host());
                        commit(paying, paying.balance().minus(staked), staked.negate(), TransactionKind.RAFFLE,
                                "Raffle #" + raffle.number() + ": the prize", RAFFLE_POT);
                        credit(RAFFLE_POT, staked, TransactionKind.RAFFLE, "Raffle #" + raffle.number() + ": the prize",
                                raffle.host());
                    }
                    result = EconomyResult.done(taken, leaving.after());
                }
            }
            raffles.put(raffle.id(), raffle);
            dirtyRaffles.add(raffle.id());
            nextRaffle = Math.max(nextRaffle, raffle.number() + 1);
            raffleCounterDirty = true;
            return result;
        }
    }

    public List<Raffle> raffles() {
        synchronized (lock) {
            return List.copyOf(raffles.values());
        }
    }

    public Optional<Raffle> raffle(UUID id) {
        synchronized (lock) {
            return Optional.ofNullable(raffles.get(id));
        }
    }

    public Optional<Raffle> raffleNumber(int number) {
        synchronized (lock) {
            return raffles.values().stream().filter(raffle -> raffle.number() == number).findFirst();
        }
    }

    /** Tickets, as many of {@code wanted} as the limits allow, paid into the raffle pot. */
    public RaffleBuy buyRaffleTickets(UUID id, UUID player, String name, int wanted, long now, Money most) {
        synchronized (lock) {
            Raffle raffle = raffles.get(id);
            if (raffle == null || raffle.over(now)) {
                return new RaffleBuy(RaffleBuy.Kind.GONE, raffle, 0, null);
            }
            if (player.equals(raffle.host())) {
                return new RaffleBuy(RaffleBuy.Kind.OWN, raffle, 0, null);
            }
            int count = RAFFLES.allowed(wanted, raffle.ticketsOf(player), raffle.perPlayer(), raffle.sold(),
                    raffle.mostTickets());
            if (count == 0) {
                boolean soldOut = raffle.mostTickets() > 0 && raffle.sold() >= raffle.mostTickets();
                return new RaffleBuy(soldOut ? RaffleBuy.Kind.SOLD_OUT : RaffleBuy.Kind.LIMIT, raffle, 0, null);
            }
            Money cost = raffle.ticketPrice().times(count);
            if (cost.isZero()) {
                Account entrant = accounts.get(player);
                if (!loaded || entrant == null || entrant.frozen()) {
                    return new RaffleBuy(RaffleBuy.Kind.REFUSED, raffle, 0, EconomyResult.failed(
                            !loaded ? Outcome.UNAVAILABLE : entrant == null ? Outcome.NO_ACCOUNT : Outcome.FROZEN,
                            cost, entrant == null ? Money.ZERO : entrant.balance()));
                }
                Raffle after = raffle.withTickets(player, count);
                raffles.put(id, after);
                dirtyRaffles.add(id);
                return new RaffleBuy(RaffleBuy.Kind.BOUGHT, after, count, EconomyResult.done(cost, entrant.balance()));
            }
            Optional<EconomyResult> refused = refuseEarly(player, cost);
            if (refused.isPresent()) {
                return new RaffleBuy(RaffleBuy.Kind.REFUSED, raffle, 0, refused.get());
            }
            Account account = accounts.get(player);
            BalanceChange leaving = rule.apply(account.balance(), cost.negate(), most, account.frozen());
            if (!leaving.allowed()) {
                return new RaffleBuy(RaffleBuy.Kind.REFUSED, raffle, 0,
                        EconomyResult.failed(leaving.outcome(), cost, account.balance()));
            }
            String reason = "Raffle #" + raffle.number() + ": " + count + " ticket(s)";
            commit(account, leaving.after(), cost.negate(), TransactionKind.RAFFLE, reason, RAFFLE_POT);
            credit(RAFFLE_POT, cost, TransactionKind.RAFFLE, reason + " by " + name, player);
            Raffle after = raffle.withTickets(player, count);
            raffles.put(id, after);
            dirtyRaffles.add(id);
            return new RaffleBuy(RaffleBuy.Kind.BOUGHT, after, count, EconomyResult.done(cost, leaving.after()));
        }
    }

    /**
     * Draws a raffle: the winner gets the prize — the item owed to them, or a server raffle's money — and the
     * host the pot less the fee. A server raffle's tickets leave the economy. Nobody bought: the item goes back.
     *
     * @param pick who wins, from the tickets
     * @param fee  the house's share, from the pot
     */
    public Optional<RaffleDraw> drawRaffle(UUID id, Function<Map<UUID, Integer>, UUID> pick, Function<Money, Money> fee,
                                           long now) {
        synchronized (lock) {
            Raffle raffle = raffles.get(id);
            if (raffle == null || !raffle.over(now)) {
                return Optional.empty();
            }
            raffles.remove(id);
            dirtyRaffles.add(id);
            UUID winner = raffle.sold() > 0 ? pick.apply(raffle.tickets()) : null;
            String label = "Raffle #" + raffle.number();
            if (winner == null) {
                return Optional.of(new RaffleDraw(raffle, null, giveBack(raffle, AuctionClaim.Reason.UNSOLD),
                        Money.ZERO, Money.ZERO));
            }
            AuctionClaim prize = null;
            if (!raffle.moneyPrize()) {
                prize = owe(raffle.item(), raffle.prizeName(), winner, AuctionClaim.Reason.RAFFLE);
            } else if (raffle.serverRaffle()) {
                credit(winner, raffle.prize(), TransactionKind.RAFFLE, label + " won", null);
            } else {
                release(RAFFLE_POT, winner, raffle.prize(), TransactionKind.RAFFLE, label + " won");
            }
            Money pot = raffle.pot();
            if (raffle.serverRaffle()) {
                destroy(RAFFLE_POT, pot, label + ": tickets", winner);
                return Optional.of(new RaffleDraw(raffle, winner, prize, Money.ZERO, pot));
            }
            Money kept = fee.apply(pot).min(pot).max(Money.ZERO);
            Money paid = pot.minus(kept);
            if (paid.isPositive()) {
                release(RAFFLE_POT, raffle.host(), paid, TransactionKind.RAFFLE, label + ": tickets sold");
            }
            if (kept.isPositive()) {
                destroy(RAFFLE_POT, kept, label + ": fee", raffle.host());
            }
            return Optional.of(new RaffleDraw(raffle, winner, prize, paid, kept));
        }
    }

    /** Calls a raffle off: every ticket is paid back and the item goes back to the host. */
    public Optional<RaffleDraw> cancelRaffle(UUID id) {
        synchronized (lock) {
            Raffle raffle = raffles.remove(id);
            if (raffle == null) {
                return Optional.empty();
            }
            dirtyRaffles.add(id);
            raffle.tickets().forEach((holder, count) -> release(RAFFLE_POT, holder, raffle.ticketPrice().times(count),
                    TransactionKind.RAFFLE, "Raffle #" + raffle.number() + " called off"));
            return Optional.of(new RaffleDraw(raffle, null, giveBack(raffle, AuctionClaim.Reason.CANCELLED),
                    Money.ZERO, Money.ZERO));
        }
    }

    /** The prize back to whoever put it up: an item owed, a player's money paid back; the server's, nothing. */
    private AuctionClaim giveBack(Raffle raffle, AuctionClaim.Reason reason) {
        if (raffle.serverRaffle()) {
            return null;
        }
        if (raffle.moneyPrize()) {
            release(RAFFLE_POT, raffle.host(), raffle.prize(), TransactionKind.RAFFLE,
                    "Raffle #" + raffle.number() + ": the prize back");
            return null;
        }
        return owe(raffle.item(), raffle.prizeName(), raffle.host(), reason);
    }

    // ---------------------------------------------------------------------------- wealth tax

    /**
     * Takes the wealth tax from every player's account — frozen ones too; a freeze stops the player, not the
     * server — and destroys it. The server's own accounts are left alone. One change, under one lock.
     *
     * @param owed what one balance owes
     */
    public TaxRun wealthTax(Function<Money, Money> owed, String reason, long now) {
        synchronized (lock) {
            int paid = 0;
            Money total = Money.ZERO;
            for (Account account : List.copyOf(accounts.values())) {
                if (isSystem(account.id()) || !account.balance().isPositive()) {
                    continue;
                }
                Money due = owed.apply(account.balance()).min(account.balance());
                if (!due.isPositive()) {
                    continue;
                }
                commit(account, account.balance().minus(due), due.negate(), TransactionKind.TAX, reason, null);
                paid++;
                total = total.plus(due);
            }
            markWealthTax(now);
            return new TaxRun(paid, total);
        }
    }

    public long lastWealthTax() {
        synchronized (lock) {
            return lastWealthTax;
        }
    }

    /** Sets when the tax last ran without taxing — switching it on starts its clock here. */
    public void markWealthTax(long at) {
        synchronized (lock) {
            lastWealthTax = at;
            wealthTaxDirty = true;
        }
    }

    // ---------------------------------------------------------------------------- writing

    /** Whether anything is waiting to be written. */
    public boolean isDirty() {
        synchronized (lock) {
            return !dirty.isEmpty() || !dirtyCredit.isEmpty() || !journal.isEmpty() || !notes.isEmpty() || !dirtyAuctions.isEmpty()
                    || !dirtyClaims.isEmpty() || !dirtyRaffles.isEmpty() || raffleCounterDirty || wealthTaxDirty;
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
            List<LotteryTicket> ticketWrites;
            Map<Money, Long> coinWrites = new HashMap<>();
            Map<UUID, Contract> contractWrites = new LinkedHashMap<>();
            Map<UUID, Loan> loanWrites = new LinkedHashMap<>();
            Map<UUID, int[]> creditWrites = new LinkedHashMap<>();
            Map<UUID, java.util.TreeMap<Long, CreditHistory.Recent>> hourWrites = new LinkedHashMap<>();
            Map<UUID, Auction> auctionWrites = new LinkedHashMap<>();
            Map<UUID, AuctionClaim> claimWrites = new LinkedHashMap<>();
            Map<UUID, Raffle> raffleWrites = new LinkedHashMap<>();
            boolean writeCounter;
            int counterNow;
            boolean writeTax;
            long taxNow;
            boolean writeLottery;
            long drawNow;
            long nextNow;
            long cleared;
            synchronized (lock) {
                if (dirty.isEmpty() && journal.isEmpty() && notes.isEmpty() && !lotteryDirty && newTickets.isEmpty()
                        && dirtyCoins.isEmpty() && dirtyContracts.isEmpty() && dirtyLoans.isEmpty() && dirtyAuctions.isEmpty()
                        && dirtyClaims.isEmpty() && dirtyRaffles.isEmpty() && !raffleCounterDirty && !wealthTaxDirty
                        && dirtyCredit.isEmpty()) {
                    return 0;
                }
                writeTax = wealthTaxDirty;
                taxNow = lastWealthTax;
                wealthTaxDirty = false;
                for (UUID id : dirtyRaffles) {
                    raffleWrites.put(id, raffles.get(id));
                }
                dirtyRaffles.clear();
                writeCounter = raffleCounterDirty;
                counterNow = nextRaffle;
                raffleCounterDirty = false;
                for (UUID id : dirtyAuctions) {
                    auctionWrites.put(id, auctions.get(id));
                }
                dirtyAuctions.clear();
                for (UUID id : dirtyClaims) {
                    claimWrites.put(id, claims.get(id));
                }
                dirtyClaims.clear();
                for (UUID id : dirtyContracts) {
                    contractWrites.put(id, contracts.get(id));
                }
                dirtyContracts.clear();
                for (UUID id : dirtyLoans) {
                    loanWrites.put(id, loans.get(id));
                }
                dirtyLoans.clear();
                for (Money value : dirtyCoins) {
                    coinWrites.put(value, circulation.getOrDefault(value, 0L));
                }
                dirtyCoins.clear();
                ticketWrites = List.copyOf(newTickets);
                writeLottery = lotteryDirty || !newTickets.isEmpty();
                drawNow = draw;
                nextNow = nextDrawAt;
                cleared = clearedDraw;
                newTickets.clear();
                lotteryDirty = false;
                clearedDraw = -1;
                for (UUID id : dirty) {
                    Account account = accounts.get(id);
                    if (account != null) {
                        changed.add(account);
                    }
                }
                lines = List.copyOf(journal);
                flushingLines = lines;
                for (UUID id : dirtyCredit) {
                    CreditHistory record = credit.getOrDefault(id, CreditHistory.EMPTY);
                    creditWrites.put(id, new int[]{record.repaidOnTime(), record.repaidLate()});
                }
                dirtyCredit.clear();
                for (Transaction line : lines) {
                    CreditHistory.Recent adds = lately(line);
                    if (!adds.equals(CreditHistory.Recent.NONE)) {
                        UUID account = line.account();
                        long hour = playMinutes.applyAsLong(account) / 60;
                        hourWrites.computeIfAbsent(account, id -> new java.util.TreeMap<>(
                                hours.getOrDefault(id, new java.util.TreeMap<>()))).merge(hour, adds,
                                CreditHistory.Recent::plus);
                    }
                }
                hourWrites.values().forEach(map -> map.headMap(map.lastKey() - HOURS_KEPT, true).clear());

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
                try (PreparedStatement total = connection.prepareStatement(
                        "INSERT INTO ledger_total (account, kind, gained, lost) VALUES (?, ?, ?, ?) "
                                + "ON CONFLICT(account, kind) DO UPDATE SET gained = gained + excluded.gained, "
                                + "lost = lost + excluded.lost")) {
                    for (Transaction line : lines) {
                        total.setString(1, line.account().toString());
                        total.setString(2, line.kind().name());
                        total.setLong(3, Math.max(0, line.delta().minor()));
                        total.setLong(4, Math.max(0, -line.delta().minor()));
                        total.addBatch();
                    }
                    total.executeBatch();
                }
                try (PreparedStatement record = connection.prepareStatement(
                        "INSERT INTO credit_record (account, on_time, late) VALUES (?, ?, ?) ON CONFLICT(account) "
                                + "DO UPDATE SET on_time = excluded.on_time, late = excluded.late")) {
                    for (Map.Entry<UUID, int[]> each : creditWrites.entrySet()) {
                        record.setString(1, each.getKey().toString());
                        record.setInt(2, each.getValue()[0]);
                        record.setInt(3, each.getValue()[1]);
                        record.addBatch();
                    }
                    record.executeBatch();
                }
                try (PreparedStatement clear = connection.prepareStatement("DELETE FROM credit_hour WHERE account = ?");
                     PreparedStatement hour = connection.prepareStatement(
                             "INSERT INTO credit_hour (account, hour, earned, spent, staked, won, received, paid) "
                                     + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                    for (Map.Entry<UUID, java.util.TreeMap<Long, CreditHistory.Recent>> each : hourWrites.entrySet()) {
                        clear.setString(1, each.getKey().toString());
                        clear.executeUpdate();
                        for (Map.Entry<Long, CreditHistory.Recent> one : each.getValue().entrySet()) {
                            hour.setString(1, each.getKey().toString());
                            hour.setLong(2, one.getKey());
                            hour.setLong(3, one.getValue().earned().minor());
                            hour.setLong(4, one.getValue().spent().minor());
                            hour.setLong(5, one.getValue().staked().minor());
                            hour.setLong(6, one.getValue().won().minor());
                            hour.setLong(7, one.getValue().received().minor());
                            hour.setLong(8, one.getValue().paid().minor());
                            hour.addBatch();
                        }
                    }
                    hour.executeBatch();
                }
                try (PreparedStatement upsert = connection.prepareStatement(
                        "INSERT INTO loan (player, name, borrowed, owed, taken_at, due_at, late_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT(player) DO UPDATE SET name = excluded.name, "
                                + "borrowed = excluded.borrowed, owed = excluded.owed, taken_at = excluded.taken_at, "
                                + "due_at = excluded.due_at, late_at = excluded.late_at");
                     PreparedStatement delete = connection.prepareStatement("DELETE FROM loan WHERE player = ?")) {
                    for (Map.Entry<UUID, Loan> each : loanWrites.entrySet()) {
                        Loan loan = each.getValue();
                        if (loan == null) {
                            delete.setString(1, each.getKey().toString());
                            delete.executeUpdate();
                            continue;
                        }
                        upsert.setString(1, loan.player().toString());
                        upsert.setString(2, loan.name());
                        upsert.setLong(3, loan.borrowed().minor());
                        upsert.setLong(4, loan.owed().minor());
                        upsert.setLong(5, loan.takenAt());
                        upsert.setLong(6, loan.dueAt());
                        upsert.setLong(7, loan.lateAt());
                        upsert.executeUpdate();
                    }
                }
                try (PreparedStatement upsert = connection.prepareStatement(
                        "INSERT INTO contract (id, employer, employer_name, employee, employee_name, wage, every, next_at, "
                                + "missed, title, since, kind) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(id) DO UPDATE "
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
                        upsert.setString(12, job.kind().name());
                        upsert.executeUpdate();
                    }
                }
                try (PreparedStatement upsert = connection.prepareStatement(
                        "INSERT INTO auction (id, seller, seller_name, item, item_name, start, buyout, bid, bidder, "
                                + "bidder_name, bids, seconds, listed_at, ends_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                                + "ON CONFLICT(id) DO UPDATE SET bid = excluded.bid, bidder = excluded.bidder, "
                                + "bidder_name = excluded.bidder_name, bids = excluded.bids, ends_at = excluded.ends_at");
                     PreparedStatement delete = connection.prepareStatement("DELETE FROM auction WHERE id = ?")) {
                    for (Map.Entry<UUID, Auction> each : auctionWrites.entrySet()) {
                        Auction auction = each.getValue();
                        if (auction == null) {
                            delete.setString(1, each.getKey().toString());
                            delete.executeUpdate();
                            continue;
                        }
                        upsert.setString(1, auction.id().toString());
                        upsert.setString(2, auction.seller().toString());
                        upsert.setString(3, auction.sellerName());
                        upsert.setBytes(4, auction.item());
                        upsert.setString(5, auction.itemName());
                        upsert.setLong(6, auction.start().minor());
                        upsert.setLong(7, auction.buyout().minor());
                        upsert.setLong(8, auction.bid().minor());
                        upsert.setString(9, auction.bidder() == null ? null : auction.bidder().toString());
                        upsert.setString(10, auction.bidderName());
                        upsert.setInt(11, auction.bids());
                        upsert.setInt(12, auction.seconds());
                        upsert.setLong(13, auction.listedAt());
                        upsert.setLong(14, auction.endsAt());
                        upsert.executeUpdate();
                    }
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT OR IGNORE INTO auction_claim (id, player, item, item_name, reason, at) "
                                + "VALUES (?, ?, ?, ?, ?, ?)");
                     PreparedStatement delete = connection.prepareStatement("DELETE FROM auction_claim WHERE id = ?")) {
                    for (Map.Entry<UUID, AuctionClaim> each : claimWrites.entrySet()) {
                        AuctionClaim claim = each.getValue();
                        if (claim == null) {
                            delete.setString(1, each.getKey().toString());
                            delete.executeUpdate();
                            continue;
                        }
                        insert.setString(1, claim.id().toString());
                        insert.setString(2, claim.player().toString());
                        insert.setBytes(3, claim.item());
                        insert.setString(4, claim.itemName());
                        insert.setString(5, claim.reason().name());
                        insert.setLong(6, claim.at());
                        insert.executeUpdate();
                    }
                }
                try (PreparedStatement upsert = connection.prepareStatement(
                        "INSERT INTO raffle (id, number, host, host_name, item, prize_name, prize, ticket_price, most_tickets, "
                                + "per_player, started_at, ends_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                                + "ON CONFLICT(id) DO NOTHING");
                     PreparedStatement delete = connection.prepareStatement("DELETE FROM raffle WHERE id = ?");
                     PreparedStatement clear = connection.prepareStatement("DELETE FROM raffle_ticket WHERE raffle = ?");
                     PreparedStatement ticket = connection.prepareStatement(
                             "INSERT INTO raffle_ticket (raffle, player, tickets, seq) VALUES (?, ?, ?, ?)")) {
                    for (Map.Entry<UUID, Raffle> each : raffleWrites.entrySet()) {
                        String key = each.getKey().toString();
                        clear.setString(1, key);
                        clear.executeUpdate();
                        Raffle raffle = each.getValue();
                        if (raffle == null) {
                            delete.setString(1, key);
                            delete.executeUpdate();
                            continue;
                        }
                        upsert.setString(1, key);
                        upsert.setInt(2, raffle.number());
                        upsert.setString(3, raffle.host() == null ? null : raffle.host().toString());
                        upsert.setString(4, raffle.hostName());
                        upsert.setBytes(5, raffle.item());
                        upsert.setString(6, raffle.prizeName());
                        upsert.setLong(7, raffle.prize().minor());
                        upsert.setLong(8, raffle.ticketPrice().minor());
                        upsert.setInt(9, raffle.mostTickets());
                        upsert.setInt(10, raffle.perPlayer());
                        upsert.setLong(11, raffle.startedAt());
                        upsert.setLong(12, raffle.endsAt());
                        upsert.executeUpdate();
                        int seq = 0;
                        for (Map.Entry<UUID, Integer> held : raffle.tickets().entrySet()) {
                            ticket.setString(1, key);
                            ticket.setString(2, held.getKey().toString());
                            ticket.setInt(3, held.getValue());
                            ticket.setInt(4, seq++);
                            ticket.addBatch();
                        }
                        ticket.executeBatch();
                    }
                }
                if (writeTax) {
                    try (PreparedStatement tax = connection.prepareStatement(
                            "INSERT INTO wealth_tax (id, last_at) VALUES (1, ?) ON CONFLICT(id) DO UPDATE SET last_at = excluded.last_at")) {
                        tax.setLong(1, taxNow);
                        tax.executeUpdate();
                    }
                }
                if (writeCounter) {
                    try (PreparedStatement counter = connection.prepareStatement(
                            "INSERT INTO raffle_counter (id, next) VALUES (1, ?) ON CONFLICT(id) DO UPDATE SET next = excluded.next")) {
                        counter.setInt(1, counterNow);
                        counter.executeUpdate();
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
                                "DELETE FROM lottery_pick WHERE draw <= ?")) {
                            clear.setLong(1, cleared);
                            clear.executeUpdate();
                        }
                    }
                    try (PreparedStatement held = connection.prepareStatement(
                            "INSERT INTO lottery_pick (draw, player, numbers) VALUES (?, ?, ?)")) {
                        for (LotteryTicket ticket : ticketWrites) {
                            held.setLong(1, drawNow);
                            held.setString(2, ticket.player().toString());
                            held.setString(3, ticket.written());
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
            synchronized (lock) {
                if (written) {
                    for (Transaction line : lines) {
                        credit.merge(line.account(), CreditHistory.EMPTY.with(line), (had, added) -> had.with(line));
                    }
                    hours.putAll(hourWrites);
                }
                flushingLines = List.of();
            }
            if (!written) {
                synchronized (lock) {
                    dirtyCredit.addAll(creditWrites.keySet());
                    changed.forEach(account -> dirty.add(account.id()));
                    journal.addAll(0, lines);
                    notes.addAll(0, noteWrites);
                    newTickets.addAll(0, ticketWrites);
                    dirtyCoins.addAll(coinWrites.keySet());
                    dirtyContracts.addAll(contractWrites.keySet());
                    dirtyLoans.addAll(loanWrites.keySet());
                    dirtyAuctions.addAll(auctionWrites.keySet());
                    dirtyClaims.addAll(claimWrites.keySet());
                    dirtyRaffles.addAll(raffleWrites.keySet());
                    raffleCounterDirty |= writeCounter;
                    wealthTaxDirty |= writeTax;
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
