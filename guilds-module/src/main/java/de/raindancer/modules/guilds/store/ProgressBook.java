package de.raindancer.modules.guilds.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.network.NetworkBus;
import de.raindancer.modules.guilds.model.Activity;
import de.raindancer.modules.guilds.model.Boost;
import de.raindancer.modules.guilds.model.Contract;
import de.raindancer.modules.guilds.model.Perk;
import de.raindancer.modules.guilds.model.Standing;
import de.raindancer.modules.guilds.model.XpSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Where a guild's experience came from, its contracts for the week, its standing (perk points, perks, prestige,
 * crowns), the server-wide boost and the crown — for the network.
 *
 * <p>Experience by source and by week is counted here at once and written with the next {@link #flush} as
 * {@code + delta}, so every server's share is in the total. A contract's progress is the same, and its being
 * finished is a change from "not done" that only one write can make: the server that makes it pays the reward,
 * once. A standing changes by the difference between what was read and what the caller made of it, checked
 * against what the database holds; two servers buying perks at once cannot both spend the same point. The
 * crown is settled by whoever moves the settled week on first.
 */
public final class ProgressBook {

    public static final String TOPIC = "guild-progress";
    private static final String BOOST = "*boost";
    private static final String CROWN = "*crown";
    private static final int ATTEMPTS = 6;
    private static final long ANNOUNCE_EVERY_NANOS = 5_000_000_000L;

    private static final class Rows {
        final Map<XpSource, Long> totals = new EnumMap<>(XpSource.class);
        final List<Contract> contracts = new ArrayList<>();
        final Map<Perk, Integer> perks = new EnumMap<>(Perk.class);
        final Map<Long, Long> weeks = new HashMap<>();
        Standing standing;
    }

    private final Database database;
    private final Freshness<String> fresh = new Freshness<>();
    private final Object flushing = new Object();
    private final Map<String, Long> announcedAt = new HashMap<>();
    private final List<Consumer<Contract>> onFinished = new CopyOnWriteArrayList<>();

    private final Map<String, Map<XpSource, Long>> totals = new HashMap<>();
    private final Map<String, List<Contract>> contracts = new HashMap<>();
    private final Map<String, Standing> standings = new HashMap<>();
    private final Map<String, Map<Long, Long>> weekXp = new HashMap<>();
    private Boost boost = Boost.NONE;
    private ProgressStorage.CrownState crown = ProgressStorage.CrownState.NONE;

    /** Counted here and not written yet. */
    private Map<String, Map<XpSource, Long>> pendingTotals = new HashMap<>();
    private Map<String, Map<Long, Long>> pendingWeeks = new HashMap<>();
    /** Per guild: "week:slot" to what was counted towards that contract. */
    private Map<String, Map<String, Long>> pendingContracts = new HashMap<>();
    private Pending inFlight = new Pending(Map.of(), Map.of(), Map.of());

    private record Pending(Map<String, Map<XpSource, Long>> totals, Map<String, Map<Long, Long>> weeks,
                           Map<String, Map<String, Long>> contracts) {
        boolean isEmpty() {
            return totals.isEmpty() && weeks.isEmpty() && contracts.isEmpty();
        }
    }

    private volatile boolean readable = true;
    private volatile NetworkBus bus = NetworkBus.NONE;

    public ProgressBook(Database database) {
        this.database = database;
    }

    // ------------------------------------------------------------------ loading and the network

    /** Reads everything. Off the server's threads. */
    public void load() {
        readable = database.isUsable() && refreshAll();
    }

    public boolean readable() {
        return readable;
    }

    /** Hears other servers' changes. Close to stop. */
    public NetworkBus.Subscription connect(NetworkBus network) {
        this.bus = network == null ? NetworkBus.NONE : network;
        NetworkBus.Subscription changes = bus.subscribe(TOPIC, event -> {
            switch (event.key()) {
                case BOOST -> refreshBoost();
                case CROWN -> refreshCrown();
                default -> refresh(event.key());
            }
        });
        NetworkBus.Subscription resync = bus.onResync(this::refreshAll);
        return () -> {
            changes.close();
            resync.close();
        };
    }

    /** Told of every contract this server's write finished — each exactly once across the network. */
    public void onContractFinished(Consumer<Contract> listener) {
        onFinished.add(listener);
    }

    private void announce(String key) {
        bus.publish(TOPIC, key);
    }

    /** Reads everything again. @return false when it could not be read, and nothing changed */
    public boolean refreshAll() {
        long started = fresh.begin();
        Optional<Map<String, Rows>> read = database.read(connection -> read(connection, null));
        Optional<Boost> boostRead = database.read(ProgressBook::boost);
        Optional<ProgressStorage.CrownState> crownRead = database.read(ProgressBook::crown);
        if (read.isEmpty() || boostRead.isEmpty() || crownRead.isEmpty()) {
            return false;
        }
        synchronized (this) {
            Set<String> known = new HashSet<>(read.get().keySet());
            known.addAll(totals.keySet());
            known.addAll(contracts.keySet());
            known.addAll(standings.keySet());
            known.addAll(weekXp.keySet());
            for (String guild : known) {
                if (fresh.check(guild, started) == Freshness.Verdict.INSTALL) {
                    install(guild, read.get().get(guild));
                }
            }
            if (fresh.check(BOOST, started) == Freshness.Verdict.INSTALL) {
                boost = boostRead.get();
            }
            if (fresh.check(CROWN, started) == Freshness.Verdict.INSTALL) {
                crown = crownRead.get();
            }
        }
        return true;
    }

    /** Reads one guild's rows again, keeping what was counted here and not written yet. */
    public boolean refresh(String guild) {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            long started = fresh.begin();
            Optional<Map<String, Rows>> read = database.read(connection -> read(connection, guild));
            if (read.isEmpty()) {
                return false;
            }
            synchronized (this) {
                Freshness.Verdict verdict = fresh.check(guild, started);
                if (verdict == Freshness.Verdict.STALE) {
                    return true;
                }
                if (verdict == Freshness.Verdict.RETRY) {
                    continue;
                }
                install(guild, read.get().get(guild));
                return true;
            }
        }
        return true;
    }

    private void refreshBoost() {
        long started = fresh.begin();
        database.read(ProgressBook::boost).ifPresent(read -> {
            synchronized (this) {
                if (fresh.check(BOOST, started) == Freshness.Verdict.INSTALL) {
                    boost = read;
                }
            }
        });
    }

    private void refreshCrown() {
        long started = fresh.begin();
        database.read(ProgressBook::crown).ifPresent(read -> {
            synchronized (this) {
                if (fresh.check(CROWN, started) == Freshness.Verdict.INSTALL) {
                    crown = read;
                }
            }
        });
    }

    /** Puts a guild's rows in place, with what was counted here on top. Under the lock. */
    private void install(String guild, Rows rows) {
        if (rows == null) {
            totals.remove(guild);
            contracts.remove(guild);
            standings.remove(guild);
            weekXp.remove(guild);
            return;
        }
        Map<XpSource, Long> sums = new EnumMap<>(XpSource.class);
        sums.putAll(rows.totals);
        Map<Long, Long> weeks = new HashMap<>(rows.weeks);
        List<Contract> list = new ArrayList<>(rows.contracts);
        for (Pending held : List.of(new Pending(pendingTotals, pendingWeeks, pendingContracts), inFlight)) {
            held.totals().getOrDefault(guild, Map.of()).forEach((source, xp) -> sums.merge(source, xp, ProgressBook::sum));
            held.weeks().getOrDefault(guild, Map.of()).forEach((week, xp) -> weeks.merge(week, xp, ProgressBook::sum));
            held.contracts().getOrDefault(guild, Map.of()).forEach((key, delta) -> {
                String[] parts = key.split(":");
                for (int index = 0; index < list.size(); index++) {
                    Contract each = list.get(index);
                    if (each.week() == Long.parseLong(parts[0]) && each.slot() == Integer.parseInt(parts[1]) && !each.done()) {
                        list.set(index, each.advanced(delta));
                    }
                }
            });
        }
        if (sums.isEmpty()) {
            totals.remove(guild);
        } else {
            totals.put(guild, sums);
        }
        if (weeks.isEmpty()) {
            weekXp.remove(guild);
        } else {
            weekXp.put(guild, weeks);
        }
        long newest = list.stream().mapToLong(Contract::week).max().orElse(0);
        List<Contract> kept = new ArrayList<>(list.stream().filter(each -> each.week() == newest)
                .sorted(Comparator.comparingInt(Contract::slot)).toList());
        if (kept.isEmpty()) {
            contracts.remove(guild);
        } else {
            contracts.put(guild, kept);
        }
        if (rows.standing == null) {
            standings.remove(guild);
        } else {
            standings.put(guild, new Standing(rows.standing.levelPoints(), rows.standing.prestige(),
                    rows.standing.crowns(), rows.perks, rows.standing.respecAt()));
        }
    }

    private static long sum(long a, long b) {
        return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
    }

    private static Map<String, Rows> read(Connection connection, String only) throws SQLException {
        Map<String, Rows> by = new LinkedHashMap<>();
        String filter = only == null ? "" : " WHERE guild = ?";
        try (PreparedStatement query = connection.prepareStatement("SELECT guild, source, xp FROM guild_xp_source" + filter)) {
            bind(query, only);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    Rows of = by.computeIfAbsent(rows.getString(1), key -> new Rows());
                    long xp = rows.getLong(3);
                    XpSource.named(rows.getString(2)).ifPresent(source -> of.totals.put(source, xp));
                }
            }
        }
        try (PreparedStatement query = connection.prepareStatement("SELECT * FROM contract" + filter)) {
            bind(query, only);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    Optional<XpSource> source = XpSource.named(rows.getString("source"));
                    if (source.isPresent()) {
                        by.computeIfAbsent(rows.getString("guild"), key -> new Rows()).contracts.add(contract(rows, source.get()));
                    }
                }
            }
        }
        try (PreparedStatement query = connection.prepareStatement("SELECT guild, week, xp FROM week_xp" + filter)) {
            bind(query, only);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    by.computeIfAbsent(rows.getString(1), key -> new Rows()).weeks.put(rows.getLong(2), rows.getLong(3));
                }
            }
        }
        try (PreparedStatement query = connection.prepareStatement("SELECT guild, perk, rank FROM guild_perk" + filter)) {
            bind(query, only);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    int rank = rows.getInt(3);
                    String of = rows.getString(1);
                    Perk.named(rows.getString(2)).ifPresent(perk ->
                            by.computeIfAbsent(of, key -> new Rows()).perks.put(perk, rank));
                }
            }
        }
        try (PreparedStatement query = connection.prepareStatement("SELECT * FROM guild_standing" + filter)) {
            bind(query, only);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    by.computeIfAbsent(rows.getString("guild"), key -> new Rows()).standing = new Standing(
                            rows.getInt("level_points"), rows.getInt("prestige"), rows.getInt("crowns"), Map.of(),
                            rows.getLong("respec_at"));
                }
            }
        }
        return by;
    }

    private static void bind(PreparedStatement query, String only) throws SQLException {
        if (only != null) {
            query.setString(1, only);
        }
    }

    private static Contract contract(ResultSet rows, XpSource source) throws SQLException {
        return new Contract(rows.getString("guild"), rows.getLong("week"), rows.getInt("slot"), source,
                rows.getString("what"), rows.getString("title"), rows.getLong("goal"), rows.getLong("progress"),
                rows.getLong("reward"), rows.getInt("done") != 0);
    }

    private static Boost boost(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT * FROM boost WHERE id = 1")) {
            if (!rows.next()) {
                return Boost.NONE;
            }
            String by = rows.getString("started_by");
            Optional<UUID> who;
            try {
                who = by == null ? Optional.empty() : Optional.of(UUID.fromString(by));
            } catch (IllegalArgumentException unreadable) {
                who = Optional.empty();
            }
            return new Boost(rows.getInt("percent"), rows.getLong("until"), who);
        }
    }

    private static ProgressStorage.CrownState crown(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT * FROM crown WHERE id = 1")) {
            return rows.next() ? new ProgressStorage.CrownState(rows.getString("guild"), rows.getLong("week"),
                    rows.getLong("settled_week")) : ProgressStorage.CrownState.NONE;
        }
    }

    // ------------------------------------------------------------------ experience by source

    public void add(String guild, XpSource source, long xp) {
        add(guild, source, xp, Long.MIN_VALUE);
    }

    /** Counts experience by source, and towards the guild's total for {@code week} — the crown's race. */
    public synchronized void add(String guild, XpSource source, long xp, long week) {
        if (xp <= 0) {
            return;
        }
        totals.computeIfAbsent(guild, key -> new EnumMap<>(XpSource.class)).merge(source, xp, ProgressBook::sum);
        pendingTotals.computeIfAbsent(guild, key -> new EnumMap<>(XpSource.class)).merge(source, xp, ProgressBook::sum);
        if (week != Long.MIN_VALUE && source != XpSource.STAFF) {
            Map<Long, Long> weeks = weekXp.computeIfAbsent(guild, key -> new HashMap<>());
            weeks.merge(week, xp, ProgressBook::sum);
            // Two weeks are all the crown ever looks at: this one and the one being settled.
            weeks.keySet().removeIf(each -> each < week - 1);
            pendingWeeks.computeIfAbsent(guild, key -> new HashMap<>()).merge(week, xp, ProgressBook::sum);
        }
    }

    /** What each guild earned in {@code week}. */
    public synchronized Map<String, Long> weekXp(long week) {
        Map<String, Long> found = new HashMap<>();
        weekXp.forEach((guild, weeks) -> {
            Long xp = weeks.get(week);
            if (xp != null) {
                found.put(guild, xp);
            }
        });
        return found;
    }

    public synchronized Map<XpSource, Long> totals(String guild) {
        return Map.copyOf(totals.getOrDefault(guild, Map.of()));
    }

    // ------------------------------------------------------------------ standing: perks, prestige, crowns

    /**
     * The guild's standing; one never written is made from {@code levelPointsSoFar} — a guild that levelled before
     * perks existed starts with a point for every level it already has.
     */
    public Standing standing(String guild, IntSupplier levelPointsSoFar) {
        synchronized (this) {
            Standing found = standings.get(guild);
            if (found != null) {
                return found;
            }
        }
        int points = levelPointsSoFar.getAsInt();
        boolean written = database.write(connection -> {
            if (exists(connection, guild)) {
                insertStanding(connection, guild, points);
            }
        });
        if (written) {
            fresh.wrote(guild);
            refresh(guild);
        }
        synchronized (this) {
            Standing found = standings.get(guild);
            return found != null ? found : new Standing(points, 0, 0, Map.of(), 0);
        }
    }

    /**
     * Changes a standing: {@code change} is given the standing as the database has it and answers the one it
     * should be — or the same, to change nothing. It may run again when another server changed the standing in
     * between, so it must decide from what it is given.
     */
    public Standing standing(String guild, IntSupplier levelPointsSoFar, UnaryOperator<Standing> change) {
        Standing known = standing(guild, levelPointsSoFar);
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            Optional<Standing> read = database.read(connection -> readStanding(connection, guild));
            if (read.isEmpty()) {
                return known;
            }
            Standing before = read.get();
            Standing after = change.apply(before);
            if (after == null || after.equals(before)) {
                return before;
            }
            boolean[] conflict = new boolean[1];
            boolean written = database.write(connection -> {
                if (!exists(connection, guild)) {
                    conflict[0] = true;
                    return;
                }
                insertStanding(connection, guild, before.levelPoints());
                // The row first: of two servers spending at once, the second waits here and then finds what the
                // first bought, instead of checking against what it saw before the first committed.
                try (PreparedStatement lock = connection.prepareStatement(
                        "UPDATE guild_standing SET level_points = level_points WHERE guild = ?")) {
                    lock.setString(1, guild);
                    lock.executeUpdate();
                }
                if (!readStanding(connection, guild).equals(before)) {
                    conflict[0] = true;
                    return;
                }
                writeStanding(connection, guild, before, after);
            });
            if (written && !conflict[0]) {
                fresh.wrote(guild);
                refresh(guild);
                announce(guild);
                synchronized (this) {
                    return standings.getOrDefault(guild, after);
                }
            }
            if (!written) {
                return known;
            }
        }
        return known;
    }

    private static Standing readStanding(Connection connection, String guild) throws SQLException {
        Rows rows = read(connection, guild).get(guild);
        if (rows == null || rows.standing == null) {
            return Standing.NONE;
        }
        return new Standing(rows.standing.levelPoints(), rows.standing.prestige(), rows.standing.crowns(), rows.perks,
                rows.standing.respecAt());
    }

    private static void insertStanding(Connection connection, String guild, int points) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO guild_standing (guild, level_points, prestige, crowns, respec_at) VALUES (?, ?, 0, 0, 0)
                ON CONFLICT (guild) DO NOTHING""")) {
            insert.setString(1, guild);
            insert.setInt(2, points);
            insert.executeUpdate();
        }
    }

    private static void writeStanding(Connection connection, String guild, Standing before, Standing after)
            throws SQLException {
        insertStanding(connection, guild, before.levelPoints());
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE guild_standing SET level_points = level_points + ?, prestige = prestige + ?,
                    crowns = crowns + ?, respec_at = ? WHERE guild = ?""")) {
            update.setInt(1, after.levelPoints() - before.levelPoints());
            update.setInt(2, after.prestige() - before.prestige());
            update.setInt(3, after.crowns() - before.crowns());
            update.setLong(4, after.respecAt());
            update.setString(5, guild);
            update.executeUpdate();
        }
        for (Perk perk : Perk.values()) {
            int was = before.rank(perk);
            int is = after.rank(perk);
            if (was == is) {
                continue;
            }
            if (is == 0) {
                try (PreparedStatement delete = connection.prepareStatement("DELETE FROM guild_perk WHERE guild = ? AND perk = ?")) {
                    delete.setString(1, guild);
                    delete.setString(2, perk.key());
                    delete.executeUpdate();
                }
            } else {
                try (PreparedStatement upsert = connection.prepareStatement("""
                        INSERT INTO guild_perk (guild, perk, rank) VALUES (?, ?, ?)
                        ON CONFLICT (guild, perk) DO UPDATE SET rank = excluded.rank""")) {
                    upsert.setString(1, guild);
                    upsert.setString(2, perk.key());
                    upsert.setInt(3, is);
                    upsert.executeUpdate();
                }
            }
        }
    }

    private static boolean exists(Connection connection, String guild) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT 1 FROM guild WHERE id = ?")) {
            query.setString(1, guild);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next();
            }
        }
    }

    // ------------------------------------------------------------------ the crown

    public synchronized ProgressStorage.CrownState crown() {
        return crown;
    }

    /**
     * Settles a week's crown: the settled week moves on to {@code next}'s, and only one server can move it. Whoever
     * gets it announces the podium and gives the champion their point.
     *
     * @return whether this call settled it
     */
    public boolean settleCrown(ProgressStorage.CrownState next) {
        boolean[] won = new boolean[1];
        boolean written = database.write(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO crown (id, guild, week, settled_week) VALUES (1, '', -1, -1)
                    ON CONFLICT (id) DO NOTHING""")) {
                insert.executeUpdate();
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE crown SET guild = ?, week = ?, settled_week = ? WHERE id = 1 AND settled_week < ?")) {
                update.setString(1, next.guild());
                update.setLong(2, next.week());
                update.setLong(3, next.settledWeek());
                update.setLong(4, next.settledWeek());
                won[0] = update.executeUpdate() == 1;
            }
        });
        fresh.wrote(CROWN);
        refreshCrown();
        if (written && won[0]) {
            announce(CROWN);
        }
        return written && won[0];
    }

    // ------------------------------------------------------------------ contracts

    /** The guild's contracts for {@code week}; dealt by {@code deal} the first time the week is asked for. */
    public List<Contract> contracts(String guild, long week, Supplier<List<Contract>> deal) {
        synchronized (this) {
            List<Contract> now = contracts.get(guild);
            if (now != null && !now.isEmpty() && now.getFirst().week() == week) {
                return List.copyOf(now);
            }
        }
        List<Contract> dealt = deal.get();
        boolean written = database.write(connection -> {
            if (!exists(connection, guild)) {
                return;
            }
            int there;
            try (PreparedStatement query = connection.prepareStatement("SELECT COUNT(*) FROM contract WHERE guild = ? AND week = ?")) {
                query.setString(1, guild);
                query.setLong(2, week);
                try (ResultSet rows = query.executeQuery()) {
                    there = rows.next() ? rows.getInt(1) : 0;
                }
            }
            if (there > 0) {
                return;
            }
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM contract WHERE guild = ? AND week <> ?")) {
                delete.setString(1, guild);
                delete.setLong(2, week);
                delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO contract (guild, week, slot, source, what, title, goal, progress, reward, done)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (guild, week, slot) DO NOTHING""")) {
                for (Contract contract : dealt) {
                    insert.setString(1, contract.guild());
                    insert.setLong(2, contract.week());
                    insert.setInt(3, contract.slot());
                    insert.setString(4, contract.source().name());
                    insert.setString(5, contract.what());
                    insert.setString(6, contract.title());
                    insert.setLong(7, contract.goal());
                    insert.setLong(8, contract.progress());
                    insert.setLong(9, contract.reward());
                    insert.setInt(10, contract.done() ? 1 : 0);
                    insert.executeUpdate();
                }
            }
        });
        if (written) {
            fresh.wrote(guild);
            refresh(guild);
            announce(guild);
        }
        synchronized (this) {
            List<Contract> now = contracts.get(guild);
            return now != null && !now.isEmpty() && now.getFirst().week() == week ? List.copyOf(now) : dealt;
        }
    }

    /**
     * Counts an activity towards this week's contracts. A contract this reaches the goal of is settled at once in
     * the database; the ones this server won are returned, each exactly once across the network — the others,
     * finished by another server's count, are that server's to pay.
     */
    public List<Contract> advance(String guild, long week, Activity activity) {
        boolean reached = false;
        synchronized (this) {
            List<Contract> now = contracts.get(guild);
            if (now == null || now.isEmpty() || now.getFirst().week() != week) {
                return List.of();
            }
            for (int index = 0; index < now.size(); index++) {
                Contract contract = now.get(index);
                if (contract.counts(activity)) {
                    Contract after = contract.advanced(activity.amount());
                    now.set(index, after);
                    pendingContracts.computeIfAbsent(guild, key -> new HashMap<>())
                            .merge(week + ":" + contract.slot(), Math.min(activity.amount(), contract.goal() - contract.progress()),
                                    ProgressBook::sum);
                    reached |= after.done();
                }
            }
        }
        if (!reached) {
            return List.of();
        }
        List<Contract> finished = write(Set.of(guild), false);
        return finished == null ? List.of() : finished;
    }

    // ------------------------------------------------------------------ the boost

    public synchronized Boost boost() {
        return boost;
    }

    public void boost(Boost next) {
        Boost wanted = next == null ? Boost.NONE : next;
        boolean written = database.write(connection -> {
            try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT INTO boost (id, percent, until, started_by) VALUES (1, ?, ?, ?)
                    ON CONFLICT (id) DO UPDATE SET percent = excluded.percent, until = excluded.until,
                        started_by = excluded.started_by""")) {
                upsert.setInt(1, wanted.percent());
                upsert.setLong(2, wanted.until());
                if (wanted.by().isPresent()) {
                    upsert.setString(3, wanted.by().get().toString());
                } else {
                    upsert.setNull(3, Types.VARCHAR);
                }
                upsert.executeUpdate();
            }
        });
        synchronized (this) {
            // In memory even when it could not be written: a boost staff started is not undone by a blink.
            boost = wanted;
        }
        fresh.wrote(BOOST);
        if (written) {
            announce(BOOST);
        }
    }

    /** A disbanded guild's rows are gone from the database; this lets go of what memory still holds. */
    public synchronized void forget(String guild) {
        totals.remove(guild);
        contracts.remove(guild);
        standings.remove(guild);
        weekXp.remove(guild);
        pendingTotals.remove(guild);
        pendingWeeks.remove(guild);
        pendingContracts.remove(guild);
    }

    // ------------------------------------------------------------------ writing

    public synchronized boolean hasUnwritten() {
        return !pendingTotals.isEmpty() || !pendingWeeks.isEmpty() || !pendingContracts.isEmpty();
    }

    /**
     * Writes what was counted since the last write, as additions. Off the server's threads; never two at once.
     * Contracts this write finished are told to {@link #onContractFinished}.
     *
     * @return whether everything queued when it started is now written
     */
    public boolean flush() {
        List<Contract> finished = write(null, true);
        if (finished == null) {
            return false;
        }
        finished.forEach(each -> onFinished.forEach(listener -> listener.accept(each)));
        return true;
    }

    /** @return the contracts this write finished, or null when it could not be written */
    private List<Contract> write(Set<String> only, boolean throttled) {
        synchronized (flushing) {
            Pending writing;
            synchronized (this) {
                if (!readable && !database.isUsable()) {
                    return null;
                }
                Map<String, Map<XpSource, Long>> totalsNow = new HashMap<>();
                Map<String, Map<Long, Long>> weeksNow = new HashMap<>();
                Map<String, Map<String, Long>> contractsNow = new HashMap<>();
                for (String guild : new HashSet<>(pendingTotals.keySet())) {
                    if (only == null || only.contains(guild)) {
                        totalsNow.put(guild, pendingTotals.remove(guild));
                    }
                }
                for (String guild : new HashSet<>(pendingWeeks.keySet())) {
                    if (only == null || only.contains(guild)) {
                        weeksNow.put(guild, pendingWeeks.remove(guild));
                    }
                }
                for (String guild : new HashSet<>(pendingContracts.keySet())) {
                    if (only == null || only.contains(guild)) {
                        contractsNow.put(guild, pendingContracts.remove(guild));
                    }
                }
                writing = new Pending(totalsNow, weeksNow, contractsNow);
                if (writing.isEmpty()) {
                    return List.of();
                }
                inFlight = writing;
            }
            List<Contract> finished = new ArrayList<>();
            boolean written = database.write(connection -> writeAll(connection, writing, finished));
            synchronized (this) {
                inFlight = new Pending(Map.of(), Map.of(), Map.of());
                if (!written) {
                    writing.totals().forEach((guild, sums) -> sums.forEach((source, xp) ->
                            pendingTotals.computeIfAbsent(guild, key -> new EnumMap<>(XpSource.class)).merge(source, xp, ProgressBook::sum)));
                    writing.weeks().forEach((guild, weeks) -> weeks.forEach((week, xp) ->
                            pendingWeeks.computeIfAbsent(guild, key -> new HashMap<>()).merge(week, xp, ProgressBook::sum)));
                    writing.contracts().forEach((guild, counted) -> counted.forEach((key, delta) ->
                            pendingContracts.computeIfAbsent(guild, ignored -> new HashMap<>()).merge(key, delta, ProgressBook::sum)));
                }
            }
            if (!written) {
                return null;
            }
            Set<String> touched = new HashSet<>(writing.totals().keySet());
            touched.addAll(writing.weeks().keySet());
            touched.addAll(writing.contracts().keySet());
            long now = System.nanoTime();
            for (String guild : touched) {
                fresh.wrote(guild);
                // A stream of experience is read back and announced every few seconds, not every time.
                Long last = announcedAt.get(guild);
                if (!throttled || last == null || now - last >= ANNOUNCE_EVERY_NANOS
                        || writing.contracts().containsKey(guild)) {
                    announcedAt.put(guild, now);
                    refresh(guild);
                    announce(guild);
                }
            }
            return finished;
        }
    }

    private static void writeAll(Connection connection, Pending writing, List<Contract> finished) throws SQLException {
        finished.clear();
        Map<String, Boolean> exist = new HashMap<>();
        for (String guild : writing.totals().keySet()) {
            exist.put(guild, exists(connection, guild));
        }
        for (String guild : writing.weeks().keySet()) {
            exist.computeIfAbsent(guild, key -> existsQuietly(connection, key));
        }
        for (String guild : writing.contracts().keySet()) {
            exist.computeIfAbsent(guild, key -> existsQuietly(connection, key));
        }
        try (PreparedStatement upsert = connection.prepareStatement("""
                INSERT INTO guild_xp_source (guild, source, xp) VALUES (?, ?, ?)
                ON CONFLICT (guild, source) DO UPDATE SET xp = guild_xp_source.xp + excluded.xp""")) {
            for (Map.Entry<String, Map<XpSource, Long>> each : writing.totals().entrySet()) {
                if (!exist.get(each.getKey())) {
                    continue;
                }
                for (Map.Entry<XpSource, Long> total : each.getValue().entrySet()) {
                    upsert.setString(1, each.getKey());
                    upsert.setString(2, total.getKey().name());
                    upsert.setLong(3, total.getValue());
                    upsert.executeUpdate();
                }
            }
        }
        try (PreparedStatement upsert = connection.prepareStatement("""
                INSERT INTO week_xp (guild, week, xp) VALUES (?, ?, ?)
                ON CONFLICT (guild, week) DO UPDATE SET xp = week_xp.xp + excluded.xp""");
             PreparedStatement prune = connection.prepareStatement("DELETE FROM week_xp WHERE guild = ? AND week < ?")) {
            for (Map.Entry<String, Map<Long, Long>> each : writing.weeks().entrySet()) {
                if (!exist.get(each.getKey())) {
                    continue;
                }
                long newest = Long.MIN_VALUE;
                for (Map.Entry<Long, Long> week : each.getValue().entrySet()) {
                    upsert.setString(1, each.getKey());
                    upsert.setLong(2, week.getKey());
                    upsert.setLong(3, week.getValue());
                    upsert.executeUpdate();
                    newest = Math.max(newest, week.getKey());
                }
                prune.setString(1, each.getKey());
                prune.setLong(2, newest - 1);
                prune.executeUpdate();
            }
        }
        try (PreparedStatement advance = connection.prepareStatement("""
                UPDATE contract SET progress = CASE WHEN progress + ? > goal THEN goal ELSE progress + ? END
                WHERE guild = ? AND week = ? AND slot = ? AND done = 0""");
             PreparedStatement finish = connection.prepareStatement("""
                UPDATE contract SET done = 1 WHERE guild = ? AND week = ? AND slot = ? AND done = 0 AND progress >= goal""");
             PreparedStatement read = connection.prepareStatement(
                     "SELECT * FROM contract WHERE guild = ? AND week = ? AND slot = ?")) {
            for (Map.Entry<String, Map<String, Long>> each : writing.contracts().entrySet()) {
                if (!exist.get(each.getKey())) {
                    continue;
                }
                for (Map.Entry<String, Long> counted : each.getValue().entrySet()) {
                    String[] parts = counted.getKey().split(":");
                    long week = Long.parseLong(parts[0]);
                    int slot = Integer.parseInt(parts[1]);
                    advance.setLong(1, counted.getValue());
                    advance.setLong(2, counted.getValue());
                    advance.setString(3, each.getKey());
                    advance.setLong(4, week);
                    advance.setInt(5, slot);
                    advance.executeUpdate();
                    finish.setString(1, each.getKey());
                    finish.setLong(2, week);
                    finish.setInt(3, slot);
                    if (finish.executeUpdate() == 1) {
                        read.setString(1, each.getKey());
                        read.setLong(2, week);
                        read.setInt(3, slot);
                        try (ResultSet rows = read.executeQuery()) {
                            if (rows.next()) {
                                XpSource.named(rows.getString("source")).ifPresent(source -> {
                                    try {
                                        finished.add(contract(rows, source));
                                    } catch (SQLException unreadable) {
                                        throw new IllegalStateException(unreadable);
                                    }
                                });
                            }
                        }
                    }
                }
            }
        }
    }

    private static boolean existsQuietly(Connection connection, String guild) {
        try {
            return exists(connection, guild);
        } catch (SQLException failed) {
            throw new IllegalStateException(failed);
        }
    }
}
