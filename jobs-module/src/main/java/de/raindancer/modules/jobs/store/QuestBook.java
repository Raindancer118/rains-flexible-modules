package de.raindancer.modules.jobs.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.network.NetworkBus;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.jobs.model.Quest;
import de.raindancer.modules.jobs.model.QuestDay;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Everybody's quests for the day, on the network: a player's quests follow them from server to server.
 *
 * <p>A player's day is read when they are first asked about and kept while they play. What they do on a server
 * is counted here at once and written as {@code + delta} on the quest's row, so what two servers count for one
 * player both count; that paying a quest is a change of state from OPEN, decided in the database, is what lets
 * it be paid once however many servers see it done. Handing out a new day, swapping a quest or ending a day is
 * a replacement checked against the revision it was made from: of two servers doing it at once, one wins and the
 * other reads what the winner wrote.
 */
public final class QuestBook {

    public static final String TOPIC = "jobs-quests";

    /** Days kept for a player who has not been seen; owed quests are kept whatever their age. */
    private static final int KEPT_DAYS = 7;
    private static final long PRUNE_EVERY_NANOS = 10L * 60 * 1_000_000_000L;
    private static final long IDLE_NANOS = 15L * 60 * 1_000_000_000L;

    private record Slot(String template, int delta) {
    }

    private static final class Entry {
        QuestDay day;
        long rev;
        Map<Integer, Slot> pending = new LinkedHashMap<>();
        Map<Integer, Slot> inFlight = Map.of();
        long touched = System.nanoTime();
        /** The newest read that is in {@link #day}, and the newest write of this server to it — see {@link #refresh}. */
        long installed;
        long wrote;
    }

    private record Loaded(QuestDay day, long rev) {
    }

    private final Database database;
    private final Map<UUID, Entry> cache = new HashMap<>();
    private final java.util.concurrent.atomic.AtomicLong stamps = new java.util.concurrent.atomic.AtomicLong();
    private volatile NetworkBus bus = NetworkBus.NONE;
    private volatile boolean readable = true;
    private long prunedAt = System.nanoTime() - PRUNE_EVERY_NANOS;

    public QuestBook(Database database) {
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

    /** A player's day as they have it now; empty when they have none, or when it could not be read. */
    public Optional<QuestDay> of(UUID player) {
        synchronized (this) {
            Entry held = cache.get(player);
            if (held != null) {
                held.touched = System.nanoTime();
                return Optional.ofNullable(held.day);
            }
        }
        refresh(player);
        synchronized (this) {
            Entry held = cache.get(player);
            return held == null ? Optional.empty() : Optional.ofNullable(held.day);
        }
    }

    /**
     * Whether this player's day can be told from "they have none": it is held, or the database answered. A day
     * never read must not be handed out afresh, or it would be written over the one that is there.
     */
    public boolean known(UUID player) {
        synchronized (this) {
            if (cache.containsKey(player)) {
                return true;
            }
        }
        return refresh(player);
    }

    /**
     * Reads a player's day again, keeping what was counted here and not written yet.
     *
     * @return false when the database could not be asked, and nothing changed
     */
    public boolean refresh(UUID player) {
        // A read that started before a write of ours finished may not have seen it, and one that started before
        // a newer read must not replace it: either would put an older picture over a newer one.
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
                held.day = overlay(read.get().day(), held);
                held.touched = System.nanoTime();
                return true;
            }
        }
        return true;
    }

    /** A write of this server to the player's rows has landed: reads that began before it are out of date. */
    private void wrote(UUID player) {
        synchronized (this) {
            Entry held = cache.get(player);
            if (held != null) {
                held.wrote = stamps.incrementAndGet();
            }
        }
    }

    private static QuestDay overlay(QuestDay day, Entry held) {
        QuestDay now = day;
        for (Map<Integer, Slot> counted : List.of(held.pending, held.inFlight)) {
            for (Map.Entry<Integer, Slot> each : counted.entrySet()) {
                int index = each.getKey();
                if (now != null && index < now.quests().size() && now.quests().get(index).template().equals(each.getValue().template())
                        && now.quests().get(index).state() == Quest.State.OPEN) {
                    now = now.with(index, now.quests().get(index).plus(each.getValue().delta()));
                }
            }
        }
        return now;
    }

    private static Loaded load(Connection connection, UUID player) throws SQLException {
        String day = null;
        int tier = 0;
        String yesterday = "";
        int rerolls = 0;
        long rev = 0;
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT day, tier, yesterday, rerolls, rev FROM quest_day WHERE player = ?")) {
            query.setString(1, player.toString());
            try (ResultSet rows = query.executeQuery()) {
                if (rows.next()) {
                    day = rows.getString(1);
                    tier = rows.getInt(2);
                    yesterday = rows.getString(3);
                    rerolls = rows.getInt(4);
                    rev = rows.getLong(5);
                }
            }
        }
        if (day == null) {
            return new Loaded(null, 0);
        }
        List<Quest> quests = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT template, amount, pay, progress, state FROM quest WHERE player = ? ORDER BY slot")) {
            query.setString(1, player.toString());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    try {
                        quests.add(new Quest(rows.getString(1), rows.getInt(2), Money.of(rows.getLong(3)), rows.getInt(4),
                                Quest.State.valueOf(rows.getString(5))));
                    } catch (IllegalArgumentException unreadable) {
                        // A state a newer version wrote: the quest is left out of this one's view.
                    }
                }
            }
        }
        return new Loaded(new QuestDay(day, tier, quests, yesterday.isEmpty() ? List.of() : List.of(yesterday.split("\n")),
                rerolls), rev);
    }

    /** Everybody with a quest whose payment was refused, read from the database. */
    public Map<UUID, QuestDay> owing() {
        Optional<List<UUID>> players = database.read(connection -> {
            List<UUID> found = new ArrayList<>();
            try (var rows = connection.createStatement().executeQuery("SELECT DISTINCT player FROM quest WHERE state = 'OWED'")) {
                while (rows.next()) {
                    try {
                        found.add(UUID.fromString(rows.getString(1)));
                    } catch (IllegalArgumentException unreadable) {
                        // not a player
                    }
                }
            }
            return found;
        });
        Map<UUID, QuestDay> owing = new LinkedHashMap<>();
        for (UUID player : players.orElse(List.of())) {
            if (refresh(player)) {
                of(player).ifPresent(day -> owing.put(player, day));
            }
        }
        return owing;
    }

    // ---------------------------------------------------------------------------- writing

    /**
     * Puts a player's day. What only counts more of the same quests is written with the next flush, as
     * additions; any other change is written now.
     */
    public void put(UUID player, QuestDay day) {
        synchronized (this) {
            Entry held = cache.get(player);
            if (held != null && held.day != null && sameShape(held.day, day) && onlyCounts(held.day, day)) {
                for (int index = 0; index < day.quests().size(); index++) {
                    int more = day.quests().get(index).progress() - held.day.quests().get(index).progress();
                    if (more > 0) {
                        Slot counted = held.pending.get(index);
                        String template = day.quests().get(index).template();
                        held.pending.put(index, counted == null || !counted.template().equals(template)
                                ? new Slot(template, more) : new Slot(template, counted.delta() + more));
                    }
                }
                held.day = day;
                held.touched = System.nanoTime();
                return;
            }
        }
        putNow(player, day);
    }

    /** Puts a player's day and writes it now. @return false, and nothing changed, when it could not be written */
    public boolean putNow(UUID player, QuestDay after) {
        if (!readable || !known(player)) {
            return false;
        }
        flush(player);
        QuestDay before;
        long rev;
        synchronized (this) {
            Entry held = cache.get(player);
            before = held.day;
            rev = held.rev;
        }
        boolean[] conflict = new boolean[1];
        boolean ran = database.write(connection -> {
            Loaded now = load(connection, player);
            if (before != null && sameShape(before, after)) {
                if (!sameStates(now.day(), before)) {
                    conflict[0] = true;
                    return;
                }
                boolean moved = false;
                for (int index = 0; index < after.quests().size(); index++) {
                    Quest was = before.quests().get(index);
                    Quest is = after.quests().get(index);
                    if (was.progress() == is.progress() && was.state() == is.state()) {
                        continue;
                    }
                    if (!allowed(was.state(), is.state())) {
                        connection.rollback();
                        conflict[0] = true;
                        return;
                    }
                    try (PreparedStatement update = connection.prepareStatement("""
                            UPDATE quest SET progress = CASE WHEN progress + ? > amount THEN amount ELSE progress + ? END,
                                state = ? WHERE player = ? AND slot = ? AND template = ? AND state = ?""")) {
                        int more = Math.max(0, is.progress() - was.progress());
                        update.setInt(1, more);
                        update.setInt(2, more);
                        update.setString(3, is.state().name());
                        update.setString(4, player.toString());
                        update.setInt(5, index);
                        update.setString(6, was.template());
                        update.setString(7, was.state().name());
                        // A change of state is the write that decides who pays: it must have changed the row.
                        if (update.executeUpdate() != 1 && was.state() != is.state()) {
                            connection.rollback();
                            conflict[0] = true;
                            return;
                        }
                    }
                    moved |= was.state() != is.state();
                }
                if (moved) {
                    // So a replacement made from the old states is refused.
                    try (PreparedStatement bump = connection.prepareStatement("UPDATE quest_day SET rev = rev + 1 WHERE player = ?")) {
                        bump.setString(1, player.toString());
                        bump.executeUpdate();
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

    /** OPEN is paid once; what was refused is owed and then paid. Nothing is ever open again. */
    private static boolean allowed(Quest.State from, Quest.State to) {
        return from == to || from == Quest.State.OPEN
                || from == Quest.State.OWED && to == Quest.State.PAID
                || from == Quest.State.PAID && to == Quest.State.OWED;
    }

    private static boolean sameStates(QuestDay now, QuestDay before) {
        if (now == null || now.quests().size() != before.quests().size()) {
            return false;
        }
        for (int index = 0; index < now.quests().size(); index++) {
            if (!now.quests().get(index).template().equals(before.quests().get(index).template())
                    || now.quests().get(index).state() != before.quests().get(index).state()) {
                return false;
            }
        }
        return true;
    }

    static void replace(Connection connection, UUID player, QuestDay day, long rev) throws SQLException {
        String yesterday = String.join("\n", day.before());
        if (rev == 0) {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO quest_day (player, day, tier, yesterday, rerolls, rev) VALUES (?, ?, ?, ?, ?, 1)""")) {
                insert.setString(1, player.toString());
                insert.setString(2, day.day());
                insert.setInt(3, day.tier());
                insert.setString(4, yesterday);
                insert.setInt(5, day.rerolls());
                insert.executeUpdate();
            }
        } else {
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE quest_day SET day = ?, tier = ?, yesterday = ?, rerolls = ?, rev = rev + 1
                    WHERE player = ? AND rev = ?""")) {
                update.setString(1, day.day());
                update.setInt(2, day.tier());
                update.setString(3, yesterday);
                update.setInt(4, day.rerolls());
                update.setString(5, player.toString());
                update.setLong(6, rev);
                if (update.executeUpdate() != 1) {
                    throw new SQLException("the day of " + player + " changed while it was being replaced");
                }
            }
        }
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM quest WHERE player = ?")) {
            delete.setString(1, player.toString());
            delete.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO quest (player, slot, template, amount, pay, progress, state) VALUES (?, ?, ?, ?, ?, ?, ?)""")) {
            for (int index = 0; index < day.quests().size(); index++) {
                Quest quest = day.quests().get(index);
                insert.setString(1, player.toString());
                insert.setInt(2, index);
                insert.setString(3, quest.template());
                insert.setInt(4, quest.amount());
                insert.setLong(5, quest.pay().minor());
                insert.setInt(6, quest.progress());
                insert.setString(7, quest.state().name());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    /** Same day, tier, rerolls and quests of the same kind, size and pay: only progress and state may differ. */
    private static boolean sameShape(QuestDay a, QuestDay b) {
        if (!a.day().equals(b.day()) || a.tier() != b.tier() || a.rerolls() != b.rerolls() || !a.before().equals(b.before())
                || a.quests().size() != b.quests().size()) {
            return false;
        }
        for (int index = 0; index < a.quests().size(); index++) {
            Quest x = a.quests().get(index);
            Quest y = b.quests().get(index);
            if (!x.template().equals(y.template()) || x.amount() != y.amount() || !x.pay().equals(y.pay())) {
                return false;
            }
        }
        return true;
    }

    private static boolean onlyCounts(QuestDay a, QuestDay b) {
        for (int index = 0; index < a.quests().size(); index++) {
            Quest x = a.quests().get(index);
            Quest y = b.quests().get(index);
            if (x.state() != y.state() || y.progress() < x.progress()) {
                return false;
            }
        }
        return true;
    }

    /** Takes a player's quests away, unless one is owed. @return false when it could not be written or one is owed */
    public boolean clear(UUID player) {
        if (!readable) {
            return false;
        }
        synchronized (this) {
            Entry held = cache.get(player);
            if (held != null) {
                held.pending.clear();
            }
        }
        boolean[] owed = new boolean[1];
        boolean written = database.write(connection -> {
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT 1 FROM quest WHERE player = ? AND state = 'OWED'")) {
                query.setString(1, player.toString());
                try (ResultSet rows = query.executeQuery()) {
                    if (rows.next()) {
                        owed[0] = true;
                        return;
                    }
                }
            }
            for (String table : List.of("quest", "quest_day")) {
                try (PreparedStatement delete = connection.prepareStatement("DELETE FROM " + table + " WHERE player = ?")) {
                    delete.setString(1, player.toString());
                    delete.executeUpdate();
                }
            }
        });
        if (written) {
            wrote(player);
            refresh(player);
            announce(player);
        }
        return written && !owed[0];
    }

    /** A player is leaving this server: what they counted is written, and their day is let go. */
    public void forget(UUID player) {
        flush(player);
        synchronized (this) {
            Entry held = cache.get(player);
            if (held != null && held.pending.isEmpty() && held.inFlight.isEmpty()) {
                cache.remove(player);
            }
        }
    }

    /** Writes what was counted since the last write, drops days long gone and lets go of idle players. */
    public void flush(LocalDate today) {
        flushAll();
        long now = System.nanoTime();
        if (readable && now - prunedAt >= PRUNE_EVERY_NANOS) {
            prunedAt = now;
            String oldest = today.minusDays(KEPT_DAYS).toString();
            database.write(connection -> {
                try (PreparedStatement delete = connection.prepareStatement("""
                        DELETE FROM quest WHERE player IN (SELECT player FROM quest_day WHERE day < ?)
                        AND player NOT IN (SELECT player FROM quest WHERE state = 'OWED')""")) {
                    delete.setString(1, oldest);
                    delete.executeUpdate();
                }
                try (PreparedStatement delete = connection.prepareStatement("""
                        DELETE FROM quest_day WHERE day < ?
                        AND player NOT IN (SELECT player FROM quest WHERE state = 'OWED')""")) {
                    delete.setString(1, oldest);
                    delete.executeUpdate();
                }
            });
        }
        synchronized (this) {
            cache.values().removeIf(held -> held.pending.isEmpty() && held.inFlight.isEmpty()
                    && now - held.touched > IDLE_NANOS);
        }
    }

    private void flushAll() {
        List<UUID> counting;
        synchronized (this) {
            counting = cache.entrySet().stream().filter(entry -> !entry.getValue().pending.isEmpty())
                    .map(Map.Entry::getKey).toList();
        }
        counting.forEach(this::flush);
    }

    /** Writes one player's counted progress as additions. */
    private boolean flush(UUID player) {
        Map<Integer, Slot> writing;
        synchronized (this) {
            Entry held = cache.get(player);
            if (held == null || held.pending.isEmpty() || !readable) {
                return true;
            }
            writing = held.pending;
            held.pending = new LinkedHashMap<>();
            held.inFlight = writing;
        }
        boolean written = database.write(connection -> {
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE quest SET progress = CASE WHEN progress + ? > amount THEN amount ELSE progress + ? END
                    WHERE player = ? AND slot = ? AND template = ? AND state = 'OPEN'""")) {
                for (Map.Entry<Integer, Slot> each : writing.entrySet()) {
                    update.setInt(1, each.getValue().delta());
                    update.setInt(2, each.getValue().delta());
                    update.setString(3, player.toString());
                    update.setInt(4, each.getKey());
                    update.setString(5, each.getValue().template());
                    update.executeUpdate();
                }
            }
        });
        synchronized (this) {
            Entry held = cache.get(player);
            if (held != null) {
                held.inFlight = Map.of();
                held.wrote = stamps.incrementAndGet();
                if (!written) {
                    writing.forEach((index, slot) -> held.pending.merge(index, slot, (a, b) ->
                            a.template().equals(b.template()) ? new Slot(a.template(), a.delta() + b.delta()) : b));
                }
            }
        }
        if (written) {
            announce(player);
        }
        return written;
    }
}
