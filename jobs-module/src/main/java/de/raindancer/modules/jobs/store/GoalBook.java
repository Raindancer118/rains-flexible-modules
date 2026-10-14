package de.raindancer.modules.jobs.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.network.NetworkBus;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.choose.ItemSelection;
import de.raindancer.modules.jobs.model.Goal;
import de.raindancer.modules.jobs.model.GoalKind;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongFunction;

/**
 * The goals on the board, who has given what, and how big each kind has learned to be — the network's one board.
 *
 * <p>What a player gives is a {@code + delta} on their row, so two servers taking hand-ins at once both count;
 * what happens once — a goal ending, a debt being paid — is a row that one server deletes and the others find
 * gone. Catches (which come one at a time and often) are counted here at once and written with the next
 * {@link #flush}. The board in memory is read again whenever another server announces a change, and keeps the
 * catches this server has not written yet. An unreadable database is never taken for an empty board.
 */
public final class GoalBook {

    public static final String TOPIC = "jobs-goals";
    private static final String KEY = "board";

    /** A hand-in as the database took it: the goal now, and how much of what was offered it counted. */
    public record Counted(Goal goal, int counted) {
    }

    private final Database database;
    private final Map<Long, Goal> active = new LinkedHashMap<>();
    private final Map<String, Integer> learned = new HashMap<>();
    /** Catches counted here and not written yet: goal number, then player. */
    private final Map<Long, Map<UUID, Integer>> pending = new LinkedHashMap<>();
    /** The catches being written right now — a refresh in that moment keeps them too. */
    private Map<Long, Map<UUID, Integer>> inFlight = Map.of();
    private final List<String> problems = new ArrayList<>();
    private final java.util.concurrent.atomic.AtomicLong stamps = new java.util.concurrent.atomic.AtomicLong();
    /** The newest read that is on the board, and the newest write of this server — see {@link #read}. */
    private long installed;
    private long wrote;
    private volatile boolean readable = true;
    private volatile NetworkBus bus = NetworkBus.NONE;

    public GoalBook(Database database) {
        this.database = database;
    }

    /** Reads the board. Off the server's threads. */
    public void load() {
        readable = database.isUsable() && read();
    }

    public boolean readable() {
        return readable;
    }

    /** Hears other servers' changes: the board is read again when one announces it. Close to stop. */
    public NetworkBus.Subscription connect(NetworkBus network) {
        this.bus = network == null ? NetworkBus.NONE : network;
        NetworkBus.Subscription changes = bus.subscribe(TOPIC, event -> read());
        NetworkBus.Subscription resync = bus.onResync(this::read);
        return () -> {
            changes.close();
            resync.close();
        };
    }

    private void announce() {
        bus.publish(TOPIC, KEY);
    }

    public synchronized List<String> problems() {
        return List.copyOf(problems);
    }

    // ---------------------------------------------------------------------------- reading

    /**
     * Reads the whole board and puts it in place. A read that began before a write of this server finished may
     * not have seen it, and one that began before a newer read must not replace it.
     *
     * @return false when it could not be read, and nothing changed
     */
    private boolean read() {
        for (int attempt = 0; attempt < 4; attempt++) {
            long started = stamps.incrementAndGet();
            Optional<Board> found = database.read(this::board);
            if (found.isEmpty()) {
                return false;
            }
            synchronized (this) {
                if (started < installed) {
                    return true;
                }
                if (started < wrote) {
                    continue;
                }
                installed = started;
                active.clear();
                found.get().goals().forEach((number, goal) -> active.put(number, withPending(goal)));
                learned.clear();
                learned.putAll(found.get().learned());
                return true;
            }
        }
        return true;
    }

    /** A write of this server has landed: reads that began before it are out of date. */
    private synchronized void wrote() {
        wrote = stamps.incrementAndGet();
    }

    private record Board(Map<Long, Goal> goals, Map<String, Integer> learned) {
    }

    private Board board(Connection connection) throws SQLException {
        Map<Long, Map<UUID, Integer>> given = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT goal, player, given FROM goal_given ORDER BY goal")) {
            while (rows.next()) {
                try {
                    given.computeIfAbsent(rows.getLong(1), key -> new LinkedHashMap<>())
                            .put(UUID.fromString(rows.getString(2)), Math.max(0, rows.getInt(3)));
                } catch (IllegalArgumentException unreadable) {
                    note("a hand-in of an unreadable player id was left out");
                }
            }
        }
        Map<Long, Goal> goals = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT * FROM goal ORDER BY number")) {
            while (rows.next()) {
                long number = rows.getLong("number");
                try {
                    goals.put(number, goal(rows, given.getOrDefault(number, Map.of())));
                } catch (IllegalArgumentException unreadable) {
                    note("goal " + number + " could not be read and was left out");
                }
            }
        }
        Map<String, Integer> sizes = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT template, amount FROM goal_learned")) {
            while (rows.next()) {
                sizes.put(rows.getString(1), Math.max(1, rows.getInt(2)));
            }
        }
        return new Board(goals, sizes);
    }

    private static Goal goal(ResultSet rows, Map<UUID, Integer> given) throws SQLException {
        String items = rows.getString("items");
        return new Goal(rows.getLong("number"), rows.getString("template"), rows.getString("title"),
                rows.getString("icon"), GoalKind.valueOf(rows.getString("kind")),
                ItemSelection.parse(items == null || items.isBlank() ? List.of() : List.of(items.split("\n"))),
                Math.max(1, rows.getInt("amount")), rows.getLong("started_at"), rows.getLong("ends_at"), given);
    }

    private synchronized void note(String problem) {
        problems.add(problem);
    }

    private Goal withPending(Goal goal) {
        Goal now = goal;
        for (Map<Long, Map<UUID, Integer>> held : List.of(pending, inFlight)) {
            for (Map.Entry<UUID, Integer> entry : held.getOrDefault(goal.number(), Map.of()).entrySet()) {
                now = now.with(entry.getKey(), entry.getValue());
            }
        }
        return now;
    }

    public synchronized List<Goal> active() {
        return List.copyOf(active.values());
    }

    public synchronized Optional<Goal> goal(long number) {
        return Optional.ofNullable(active.get(number));
    }

    public synchronized Optional<Integer> learned(String template) {
        return Optional.ofNullable(learned.get(template));
    }

    // ---------------------------------------------------------------------------- the board

    /**
     * Puts a goal on the board under the number it has. @return false, and nothing changed, when it could not be
     * written or the board has a goal of that number or kind already
     */
    public boolean start(Goal goal) {
        if (!readable) {
            return false;
        }
        boolean[] put = new boolean[1];
        boolean ran = database.write(connection -> {
            lock(connection);
            if (taken(connection, goal.number(), goal.template())) {
                return;
            }
            insert(connection, goal);
            try (PreparedStatement counter = connection.prepareStatement(
                    "UPDATE jobs_counter SET next = ? WHERE id = 1 AND next <= ?")) {
                counter.setLong(1, goal.number() + 1);
                counter.setLong(2, goal.number());
                counter.executeUpdate();
            }
            put[0] = true;
        });
        wrote();
        read();
        if (ran && put[0]) {
            announce();
        }
        return ran && put[0];
    }

    /**
     * Puts a goal up under the next number, unless the board already holds {@code wanted} goals or one of the
     * kind {@code make} builds. The number, the count and the kind are checked in the one write that inserts, so
     * two servers filling the board at once put up two goals between them only when it has room for both.
     */
    public Optional<Goal> start(int wanted, LongFunction<Goal> make) {
        if (!readable) {
            return Optional.empty();
        }
        Goal[] made = new Goal[1];
        boolean ran = database.write(connection -> {
            lock(connection);
            long number = counter(connection);
            Goal goal = make.apply(number);
            if (count(connection) >= wanted || taken(connection, goal.number(), goal.template())) {
                return;
            }
            insert(connection, goal);
            try (PreparedStatement counter = connection.prepareStatement("UPDATE jobs_counter SET next = ? WHERE id = 1")) {
                counter.setLong(1, number + 1);
                counter.executeUpdate();
            }
            made[0] = goal;
        });
        wrote();
        read();
        if (ran && made[0] != null) {
            announce();
        }
        return ran ? Optional.ofNullable(made[0]) : Optional.empty();
    }

    /**
     * Counts what a player gave, never past what the goal still needs — in the database, where the goal's
     * progress is the sum of every server's hand-ins.
     *
     * @return the goal as it is now, or empty when there is no such goal or it could not be saved
     */
    public Optional<Goal> add(long number, UUID player, int count) {
        return give(number, player, count).map(Counted::goal);
    }

    /** As {@link #add}, saying how much of {@code count} the goal took: another server may have filled it first. */
    public Optional<Counted> give(long number, UUID player, int count) {
        if (!readable) {
            return Optional.empty();
        }
        Counted[] result = new Counted[1];
        boolean ran = database.write(connection -> {
            lock(connection);
            Goal goal = load(connection, number);
            if (goal == null) {
                return;
            }
            int took = Math.min(Math.max(0, count), goal.left());
            if (took > 0) {
                upsertGiven(connection, number, player, took);
            }
            result[0] = new Counted(took > 0 ? goal.with(player, took) : goal, took);
        });
        if (!ran) {
            return Optional.empty();
        }
        wrote();
        if (result[0] == null) {
            read();
            return Optional.empty();
        }
        read();
        if (result[0].counted() > 0) {
            announce();
        }
        return Optional.of(new Counted(goal(number).orElse(result[0].goal()), result[0].counted()));
    }

    /** The same, written with the next flush rather than now — for catches, which come one at a time and often. */
    public synchronized Optional<Goal> addLater(long number, UUID player, int count) {
        Goal goal = active.get(number);
        if (goal == null || !readable) {
            return Optional.empty();
        }
        Goal changed = goal.with(player, count);
        int taken = changed.givenBy(player) - goal.givenBy(player);
        if (taken > 0) {
            pending.computeIfAbsent(number, key -> new LinkedHashMap<>()).merge(player, taken, Integer::sum);
        }
        active.put(number, changed);
        return Optional.of(changed);
    }

    /**
     * Takes a goal off the board, with what was given to it — in one step, so of the servers that see it done
     * only one gets it back and pays it out.
     *
     * @return it, or empty when there was none or it could not be saved
     */
    public Optional<Goal> end(long number) {
        if (!readable) {
            return Optional.empty();
        }
        flush();
        Goal[] ended = new Goal[1];
        boolean ran = database.write(connection -> {
            lock(connection);
            Goal goal = load(connection, number);
            if (goal == null) {
                return;
            }
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM goal_given WHERE goal = ?")) {
                delete.setLong(1, number);
                delete.executeUpdate();
            }
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM goal WHERE number = ?")) {
                delete.setLong(1, number);
                delete.executeUpdate();
            }
            ended[0] = goal;
        });
        wrote();
        read();
        if (ran && ended[0] != null) {
            synchronized (this) {
                pending.remove(number);
            }
            announce();
        }
        return ran ? Optional.ofNullable(ended[0]) : Optional.empty();
    }

    public boolean learn(String template, int amount) {
        if (!readable) {
            return false;
        }
        boolean written = database.write(connection -> {
            try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT INTO goal_learned (template, amount) VALUES (?, ?)
                    ON CONFLICT (template) DO UPDATE SET amount = excluded.amount""")) {
                upsert.setString(1, template);
                upsert.setInt(2, Math.max(1, amount));
                upsert.executeUpdate();
            }
        });
        if (written) {
            synchronized (this) {
                learned.put(template, Math.max(1, amount));
                wrote = stamps.incrementAndGet();
            }
            announce();
        }
        return written;
    }

    // ---------------------------------------------------------------------------- what the treasury owes

    /** What the treasury refused to pay out, per player, still to be paid. Read from the database each time. */
    public Map<UUID, Money> owed() {
        return database.read(connection -> {
            Map<UUID, Money> owed = new LinkedHashMap<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT player, minor FROM goal_owed")) {
                while (rows.next()) {
                    try {
                        owed.put(UUID.fromString(rows.getString(1)), Money.of(Math.max(0, rows.getLong(2))));
                    } catch (IllegalArgumentException unreadable) {
                        note("a debt to an unreadable player id was left out");
                    }
                }
            }
            return owed;
        }).orElse(Map.of());
    }

    /** Adds to what a player is owed. @return false, and nothing changed, when it could not be saved */
    public boolean owe(UUID player, Money amount) {
        if (!readable) {
            return false;
        }
        return database.write(connection -> {
            try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT INTO goal_owed (player, minor) VALUES (?, ?)
                    ON CONFLICT (player) DO UPDATE SET minor = goal_owed.minor + excluded.minor""")) {
                upsert.setString(1, player.toString());
                upsert.setLong(2, amount.minor());
                upsert.executeUpdate();
            }
        });
    }

    /**
     * Takes what a player is owed off the books, to pay it: of the servers trying, one gets it. Whoever cannot
     * pay it {@link #owe}s it again.
     */
    public Optional<Money> claimOwed(UUID player) {
        long[] claimed = {-1};
        boolean ran = database.write(connection -> {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM goal_owed WHERE player = ? RETURNING minor")) {
                delete.setString(1, player.toString());
                try (ResultSet rows = delete.executeQuery()) {
                    if (rows.next()) {
                        claimed[0] = rows.getLong(1);
                    }
                }
            }
        });
        return ran && claimed[0] >= 0 ? Optional.of(Money.of(claimed[0])) : Optional.empty();
    }

    // ---------------------------------------------------------------------------- writing

    /** Writes the catches counted since the last write, as additions. Off the server's threads. */
    public boolean flush() {
        Map<Long, Map<UUID, Integer>> writing;
        synchronized (this) {
            if (pending.isEmpty() || !readable) {
                return true;
            }
            writing = new LinkedHashMap<>(pending);
            pending.clear();
            inFlight = writing;
        }
        boolean written = database.write(connection -> {
            lock(connection);
            for (Map.Entry<Long, Map<UUID, Integer>> each : writing.entrySet()) {
                Goal goal = load(connection, each.getKey());
                if (goal == null) {
                    continue;
                }
                int left = goal.left();
                for (Map.Entry<UUID, Integer> given : each.getValue().entrySet()) {
                    int took = Math.min(given.getValue(), left);
                    if (took > 0) {
                        upsertGiven(connection, each.getKey(), given.getKey(), took);
                        left -= took;
                    }
                }
            }
        });
        synchronized (this) {
            inFlight = Map.of();
            wrote = stamps.incrementAndGet();
            if (!written) {
                writing.forEach((number, players) -> players.forEach((player, count) ->
                        pending.computeIfAbsent(number, key -> new LinkedHashMap<>()).merge(player, count, Integer::sum)));
            }
        }
        if (written) {
            read();
            announce();
        }
        return written;
    }

    // ---------------------------------------------------------------------------- sql

    static void lock(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE jobs_counter SET next = next WHERE id = 1");
        }
    }

    static long counter(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT next FROM jobs_counter WHERE id = 1")) {
            return rows.next() ? rows.getLong(1) : 1;
        }
    }

    private static int count(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM goal")) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    private static boolean taken(Connection connection, long number, String template) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT 1 FROM goal WHERE number = ? OR template = ?")) {
            query.setLong(1, number);
            query.setString(2, template);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next();
            }
        }
    }

    static void insert(Connection connection, Goal goal) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO goal (number, template, title, icon, kind, items, amount, started_at, ends_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
            insert.setLong(1, goal.number());
            insert.setString(2, goal.template());
            insert.setString(3, goal.title());
            insert.setString(4, goal.icon());
            insert.setString(5, goal.kind().name());
            insert.setString(6, String.join("\n", lines(goal.items())));
            insert.setInt(7, goal.amount());
            insert.setLong(8, goal.startedAt());
            insert.setLong(9, goal.endsAt());
            insert.executeUpdate();
        }
        for (Map.Entry<UUID, Integer> given : goal.given().entrySet()) {
            upsertGiven(connection, goal.number(), given.getKey(), given.getValue());
        }
    }

    static void upsertGiven(Connection connection, long goal, UUID player, int count) throws SQLException {
        try (PreparedStatement upsert = connection.prepareStatement("""
                INSERT INTO goal_given (goal, player, given) VALUES (?, ?, ?)
                ON CONFLICT (goal, player) DO UPDATE SET given = goal_given.given + excluded.given""")) {
            upsert.setLong(1, goal);
            upsert.setString(2, player.toString());
            upsert.setInt(3, count);
            upsert.executeUpdate();
        }
    }

    /** One goal as the database has it right now, inside the caller's write; null when there is none. */
    private Goal load(Connection connection, long number) throws SQLException {
        Map<UUID, Integer> given = new LinkedHashMap<>();
        try (PreparedStatement query = connection.prepareStatement("SELECT player, given FROM goal_given WHERE goal = ?")) {
            query.setLong(1, number);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    try {
                        given.put(UUID.fromString(rows.getString(1)), Math.max(0, rows.getInt(2)));
                    } catch (IllegalArgumentException unreadable) {
                        note("a hand-in of an unreadable player id was left out");
                    }
                }
            }
        }
        try (PreparedStatement query = connection.prepareStatement("SELECT * FROM goal WHERE number = ?")) {
            query.setLong(1, number);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? goal(rows, given) : null;
            }
        }
    }

    private static List<String> lines(ItemSelection items) {
        List<String> lines = new ArrayList<>();
        items.categories().forEach(category -> lines.add(category.name().toLowerCase(java.util.Locale.ROOT)));
        lines.addAll(items.items());
        items.except().forEach(each -> lines.add("!" + each));
        return lines;
    }
}
