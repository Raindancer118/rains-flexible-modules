package de.raindancer.modules.jobs.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.network.NetworkBus;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.jobs.model.Order;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Who has which order and how many they took today, on the network — a player's order runs on whichever server
 * they are on. Counted progress is written as {@code + delta}; an order paid, failed or given up is a change of
 * state from the one it was read in, decided in the database, so it is paid once however many servers see it
 * done. Taking an order or re-rolling is a replacement checked against the revision it was made from.
 */
public final class OrderBook {

    public static final String TOPIC = "jobs-orders";

    private static final long IDLE_NANOS = 15L * 60 * 1_000_000_000L;

    /**
     * One player's day: how many orders and re-rolls, and the order they are on, if any.
     *
     * @param offered whether they were made an offer they have not taken — asking again then is a re-roll. Kept
     *                here, not in memory, so leaving and coming back does not hand the re-rolls out again
     */
    public record Ledger(String day, int taken, int rerolls, boolean offered, Order order) {

        public Optional<Order> current() {
            return Optional.ofNullable(order);
        }

        public Ledger with(Order next) {
            return new Ledger(day, taken, rerolls, offered, next);
        }
    }

    private record Counted(String work, long startedAt, int delta) {
    }

    private static final class Entry {
        Ledger ledger;
        long rev;
        Counted pending;
        Counted inFlight;
        long touched = System.nanoTime();
        /** The newest read that is in {@link #ledger}, and the newest write of this server to it. */
        long installed;
        long wrote;
    }

    private record Loaded(Ledger ledger, long rev) {
    }

    private final Database database;
    private final Map<UUID, Entry> cache = new HashMap<>();
    private final java.util.concurrent.atomic.AtomicLong stamps = new java.util.concurrent.atomic.AtomicLong();
    private volatile NetworkBus bus = NetworkBus.NONE;
    private volatile boolean readable = true;

    public OrderBook(Database database) {
        this.database = database;
    }

    public void load() {
        readable = database.isUsable();
    }

    public boolean readable() {
        return readable;
    }

    /** Hears other servers' changes to a player they have here. Close to stop. */
    public NetworkBus.Subscription connect(NetworkBus network) {
        this.bus = network == null ? NetworkBus.NONE : network;
        NetworkBus.Subscription changes = bus.subscribe(TOPIC, event -> {
            try {
                UUID player = UUID.fromString(event.key());
                boolean here;
                synchronized (this) {
                    here = cache.containsKey(player);
                }
                if (here) {
                    refresh(player);
                }
            } catch (IllegalArgumentException notAPlayer) {
                // not about anybody
            }
        });
        NetworkBus.Subscription resync = bus.onResync(() -> {
            List<UUID> held;
            synchronized (this) {
                held = List.copyOf(cache.keySet());
            }
            held.forEach(this::refresh);
        });
        return () -> {
            changes.close();
            resync.close();
        };
    }

    private void announce(UUID player) {
        bus.publish(TOPIC, player.toString());
    }

    // ---------------------------------------------------------------------------- reading

    public Optional<Ledger> of(UUID player) {
        synchronized (this) {
            Entry held = cache.get(player);
            if (held != null) {
                held.touched = System.nanoTime();
                return Optional.ofNullable(held.ledger);
            }
        }
        refresh(player);
        synchronized (this) {
            Entry held = cache.get(player);
            return held == null ? Optional.empty() : Optional.ofNullable(held.ledger);
        }
    }

    /** Whether this player's ledger can be told from "they have none" — see {@link QuestBook#known}. */
    public boolean known(UUID player) {
        synchronized (this) {
            if (cache.containsKey(player)) {
                return true;
            }
        }
        return refresh(player);
    }

    /** Reads a player's ledger again, keeping what was counted here and not written yet. */
    public boolean refresh(UUID player) {
        // As QuestBook.refresh: never an older picture over a newer one.
        for (int attempt = 0; attempt < 4; attempt++) {
            long started = stamps.incrementAndGet();
            Optional<Loaded> read = database.read(connection -> load(connection, player));
            if (read.isEmpty()) {
                return false;
            }
            synchronized (this) {
                Entry held = cache.computeIfAbsent(player, key -> new Entry());
                if (started < held.installed) {
                    return true;
                }
                if (started < held.wrote) {
                    continue;
                }
                held.installed = started;
                held.rev = read.get().rev();
                held.ledger = overlay(read.get().ledger(), held);
                held.touched = System.nanoTime();
                return true;
            }
        }
        return true;
    }

    private void wrote(UUID player) {
        synchronized (this) {
            Entry held = cache.get(player);
            if (held != null) {
                held.wrote = stamps.incrementAndGet();
            }
        }
    }

    private static Ledger overlay(Ledger ledger, Entry held) {
        Ledger now = ledger;
        for (Counted counted : java.util.Arrays.asList(held.pending, held.inFlight)) {
            if (counted != null && now != null && now.order() != null && now.order().work().equals(counted.work())
                    && now.order().startedAt() == counted.startedAt() && now.order().state() == Order.State.OPEN) {
                now = now.with(now.order().plus(counted.delta()));
            }
        }
        return now;
    }

    private static Loaded load(Connection connection, UUID player) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT day, taken, rerolls, offered, rev, work, says, units, pay, started_at, ends_at, progress, state
                FROM order_ledger WHERE player = ?""")) {
            query.setString(1, player.toString());
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? read(rows) : new Loaded(null, 0);
            }
        }
    }

    private static Loaded read(ResultSet rows) throws SQLException {
        Order order = null;
        String work = rows.getString("work");
        if (work != null) {
            try {
                order = new Order(work, rows.getString("says"), rows.getInt("units"), Money.of(rows.getLong("pay")),
                        rows.getLong("started_at"), rows.getLong("ends_at"), rows.getInt("progress"),
                        Order.State.valueOf(rows.getString("state")));
            } catch (IllegalArgumentException unreadable) {
                // A state a newer version wrote: the ledger is read without the order.
            }
        }
        return new Loaded(new Ledger(rows.getString("day"), rows.getInt("taken"), rows.getInt("rerolls"),
                rows.getInt("offered") != 0, order), rows.getLong("rev"));
    }

    /**
     * Everybody whose order has run out of time or was refused its payment, read from the database — what a
     * server's minute has to settle.
     */
    public Map<UUID, Ledger> actionable(long now) {
        Optional<List<UUID>> players = database.read(connection -> {
            List<UUID> found = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT player FROM order_ledger WHERE state = 'OWED' OR (state = 'OPEN' AND ends_at <= ?)")) {
                query.setLong(1, now);
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        try {
                            found.add(UUID.fromString(rows.getString(1)));
                        } catch (IllegalArgumentException unreadable) {
                            // not a player
                        }
                    }
                }
            }
            return found;
        });
        Map<UUID, Ledger> due = new LinkedHashMap<>();
        for (UUID player : players.orElse(List.of())) {
            if (refresh(player)) {
                of(player).ifPresent(ledger -> due.put(player, ledger));
            }
        }
        return due;
    }

    // ---------------------------------------------------------------------------- writing

    /** Puts a player's ledger: counted progress with the next flush as an addition, any other change at once. */
    public void put(UUID player, Ledger ledger) {
        synchronized (this) {
            Entry held = cache.get(player);
            if (held != null && held.ledger != null && sameShape(held.ledger, ledger) && onlyCounts(held.ledger, ledger)) {
                int more = ledger.order().progress() - held.ledger.order().progress();
                if (more > 0) {
                    held.pending = held.pending != null && held.pending.work().equals(ledger.order().work())
                            && held.pending.startedAt() == ledger.order().startedAt()
                            ? new Counted(held.pending.work(), held.pending.startedAt(), held.pending.delta() + more)
                            : new Counted(ledger.order().work(), ledger.order().startedAt(), more);
                }
                held.ledger = ledger;
                held.touched = System.nanoTime();
                return;
            }
        }
        putNow(player, ledger);
    }

    /** @return false, and nothing changed, when it could not be written */
    public boolean putNow(UUID player, Ledger after) {
        if (!readable || !known(player)) {
            return false;
        }
        flush(player);
        Ledger before;
        long rev;
        synchronized (this) {
            Entry held = cache.get(player);
            before = held.ledger;
            rev = held.rev;
        }
        boolean[] conflict = new boolean[1];
        boolean ran = database.write(connection -> {
            Loaded now = load(connection, player);
            if (before != null && sameShape(before, after) && before.order() != null) {
                if (now.ledger() == null || now.ledger().order() == null || now.ledger().order().state() != before.order().state()
                        || !now.ledger().order().work().equals(before.order().work())
                        || now.ledger().order().startedAt() != before.order().startedAt()) {
                    conflict[0] = true;
                    return;
                }
                Order was = before.order();
                Order is = after.order();
                if (was.progress() == is.progress() && was.state() == is.state()) {
                    return;
                }
                if (!allowed(was.state(), is.state())) {
                    conflict[0] = true;
                    return;
                }
                boolean moves = was.state() != is.state();
                int more = Math.max(0, is.progress() - was.progress());
                // A change of state is the write that decides who pays: it must have changed the row, and it
                // moves rev so a replacement made from the old state is refused.
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE order_ledger SET progress = CASE WHEN progress + ? > units THEN units ELSE progress + ? END,
                            state = ?, rev = rev + ? WHERE player = ? AND work = ? AND started_at = ? AND state = ?""")) {
                    update.setInt(1, more);
                    update.setInt(2, more);
                    update.setString(3, is.state().name());
                    update.setInt(4, moves ? 1 : 0);
                    update.setString(5, player.toString());
                    update.setString(6, was.work());
                    update.setLong(7, was.startedAt());
                    update.setString(8, was.state().name());
                    if (update.executeUpdate() != 1 && moves) {
                        connection.rollback();
                        conflict[0] = true;
                    }
                }
                return;
            }
            if (now.rev() != rev) {
                conflict[0] = true;
                return;
            }
            replace(connection, player, after, rev);
        });
        wrote(player);
        refresh(player);
        if (ran && !conflict[0]) {
            announce(player);
        }
        return ran && !conflict[0];
    }

    static void replace(Connection connection, UUID player, Ledger ledger, long rev) throws SQLException {
        String sql = rev == 0
                ? """
                INSERT INTO order_ledger (day, taken, rerolls, offered, work, says, units, pay, started_at, ends_at,
                    progress, state, player, rev) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)"""
                : """
                UPDATE order_ledger SET day = ?, taken = ?, rerolls = ?, offered = ?, work = ?, says = ?, units = ?,
                    pay = ?, started_at = ?, ends_at = ?, progress = ?, state = ?, rev = rev + 1
                WHERE player = ? AND rev = ?""";
        try (PreparedStatement write = connection.prepareStatement(sql)) {
            write.setString(1, ledger.day());
            write.setInt(2, ledger.taken());
            write.setInt(3, ledger.rerolls());
            write.setInt(4, ledger.offered() ? 1 : 0);
            Order order = ledger.order();
            write.setString(5, order == null ? null : order.work());
            write.setString(6, order == null ? null : order.says());
            if (order == null) {
                write.setNull(7, Types.BIGINT);
                write.setNull(8, Types.BIGINT);
                write.setNull(9, Types.BIGINT);
                write.setNull(10, Types.BIGINT);
                write.setNull(11, Types.BIGINT);
                write.setNull(12, Types.VARCHAR);
            } else {
                write.setInt(7, order.units());
                write.setLong(8, order.pay().minor());
                write.setLong(9, order.startedAt());
                write.setLong(10, order.endsAt());
                write.setInt(11, order.progress());
                write.setString(12, order.state().name());
            }
            write.setString(13, player.toString());
            if (rev != 0) {
                write.setLong(14, rev);
            }
            if (write.executeUpdate() != 1) {
                throw new SQLException("the ledger of " + player + " changed while it was being replaced");
            }
        }
    }

    /** Same day, counts and offer, and the same order but for its progress and state. */
    private static boolean sameShape(Ledger a, Ledger b) {
        if (!a.day().equals(b.day()) || a.taken() != b.taken() || a.rerolls() != b.rerolls() || a.offered() != b.offered()) {
            return false;
        }
        if (a.order() == null || b.order() == null) {
            return a.order() == null && b.order() == null;
        }
        Order x = a.order();
        Order y = b.order();
        return x.work().equals(y.work()) && x.says().equals(y.says()) && x.units() == y.units() && x.pay().equals(y.pay())
                && x.startedAt() == y.startedAt() && x.endsAt() == y.endsAt();
    }

    /** OPEN ends once; what was refused is owed and then paid. Nothing is ever open again. */
    private static boolean allowed(Order.State from, Order.State to) {
        return from == to || from == Order.State.OPEN
                || from == Order.State.OWED && to == Order.State.PAID
                || from == Order.State.PAID && to == Order.State.OWED;
    }

    private static boolean onlyCounts(Ledger a, Ledger b) {
        return a.order() != null && a.order().state() == b.order().state() && b.order().progress() >= a.order().progress();
    }

    /** A player is leaving this server: what they counted is written, and their ledger is let go. */
    public void forget(UUID player) {
        flush(player);
        synchronized (this) {
            Entry held = cache.get(player);
            if (held != null && held.pending == null && held.inFlight == null) {
                cache.remove(player);
            }
        }
    }

    /** Writes what was counted since the last write, and lets go of idle players. */
    public void flush() {
        List<UUID> counting;
        synchronized (this) {
            counting = cache.entrySet().stream().filter(entry -> entry.getValue().pending != null)
                    .map(Map.Entry::getKey).toList();
        }
        counting.forEach(this::flush);
        long now = System.nanoTime();
        synchronized (this) {
            cache.values().removeIf(held -> held.pending == null && held.inFlight == null && now - held.touched > IDLE_NANOS);
        }
    }

    private boolean flush(UUID player) {
        Counted writing;
        synchronized (this) {
            Entry held = cache.get(player);
            if (held == null || held.pending == null || !readable) {
                return true;
            }
            writing = held.pending;
            held.pending = null;
            held.inFlight = writing;
        }
        boolean written = database.write(connection -> {
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE order_ledger SET progress = CASE WHEN progress + ? > units THEN units ELSE progress + ? END
                    WHERE player = ? AND work = ? AND started_at = ? AND state = 'OPEN'""")) {
                update.setInt(1, writing.delta());
                update.setInt(2, writing.delta());
                update.setString(3, player.toString());
                update.setString(4, writing.work());
                update.setLong(5, writing.startedAt());
                update.executeUpdate();
            }
        });
        synchronized (this) {
            Entry held = cache.get(player);
            if (held != null) {
                held.inFlight = null;
                held.wrote = stamps.incrementAndGet();
                if (!written) {
                    held.pending = held.pending != null && held.pending.work().equals(writing.work())
                            && held.pending.startedAt() == writing.startedAt()
                            ? new Counted(writing.work(), writing.startedAt(), held.pending.delta() + writing.delta())
                            : writing;
                }
            }
        }
        if (written) {
            announce(player);
        }
        return written;
    }
}
