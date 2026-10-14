package de.raindancer.modules.jobs.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.choose.ItemSelection;
import de.raindancer.modules.jobs.model.Goal;
import de.raindancer.modules.jobs.model.GoalKind;
import de.raindancer.modules.jobs.model.Order;
import de.raindancer.modules.jobs.model.Quest;
import de.raindancer.modules.jobs.model.QuestDay;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Brings what the module kept before the network — {@code goals.yml}, {@code quest-progress.yml} and
 * {@code order-progress.yml}, this server's documents — into the shared database, once per server and file.
 *
 * <p>What the network already has wins: a player who already has a day or an order there keeps it, and a board
 * that already has goals is not given a second board. What is owed, and what each kind has learned, is added.
 * A document that cannot be read is left for the next start; the old documents are never removed.
 */
public final class JobsImport {

    public static final String GOALS = "goals";
    public static final String QUESTS = "quests";
    public static final String ORDERS = "orders";

    private final Database database;
    private final String server;
    private final Consumer<String> problems;

    public JobsImport(Database database, String server, Consumer<String> problems) {
        this.database = database;
        this.server = server == null ? "" : server;
        this.problems = problems;
    }

    /** @return the sources brought in now */
    public List<String> run(YamlStore goals, YamlStore quests, YamlStore orders) {
        List<String> brought = new ArrayList<>();
        brought.addAll(bring(GOALS, goals, this::goals));
        brought.addAll(bring(QUESTS, quests, this::quests));
        brought.addAll(bring(ORDERS, orders, this::orders));
        return brought;
    }

    private interface Reading {
        void into(Connection connection, YamlConfiguration yaml) throws SQLException;
    }

    private List<String> bring(String source, YamlStore store, Reading reading) {
        if (!database.isUsable()) {
            return List.of();
        }
        Optional<Boolean> done = database.read(connection -> imported(connection, source));
        if (done.isEmpty() || done.get()) {
            return List.of();
        }
        Optional<YamlConfiguration> yaml = store.readIfReadable();
        if (yaml.isEmpty()) {
            problems.accept(source + ": the old document could not be read; it is tried again at the next start");
            return List.of();
        }
        boolean written = database.write(connection -> {
            if (imported(connection, source)) {
                return;
            }
            try (PreparedStatement mark = connection.prepareStatement("INSERT INTO jobs_import (source, server) VALUES (?, ?)")) {
                mark.setString(1, source);
                mark.setString(2, server);
                mark.executeUpdate();
            }
            reading.into(connection, yaml.get());
        });
        return written ? List.of(source) : List.of();
    }

    private boolean imported(Connection connection, String source) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT 1 FROM jobs_import WHERE source = ? AND server = ?")) {
            query.setString(1, source);
            query.setString(2, server);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next();
            }
        }
    }

    // ---------------------------------------------------------------------------- goals

    private void goals(Connection connection, YamlConfiguration yaml) throws SQLException {
        GoalBook.lock(connection);
        ConfigurationSection learnt = yaml.getConfigurationSection("learned");
        if (learnt != null) {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO goal_learned (template, amount) VALUES (?, ?) ON CONFLICT (template) DO NOTHING")) {
                for (String template : learnt.getKeys(false)) {
                    insert.setString(1, template);
                    insert.setInt(2, Math.max(1, learnt.getInt(template)));
                    insert.executeUpdate();
                }
            }
        }
        ConfigurationSection debts = yaml.getConfigurationSection("owed");
        if (debts != null) {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO goal_owed (player, minor) VALUES (?, ?)
                    ON CONFLICT (player) DO UPDATE SET minor = goal_owed.minor + excluded.minor""")) {
                for (String id : debts.getKeys(false)) {
                    try {
                        insert.setString(1, UUID.fromString(id).toString());
                        insert.setLong(2, Money.of(Math.max(0, debts.getLong(id))).minor());
                        insert.executeUpdate();
                    } catch (IllegalArgumentException unreadable) {
                        problems.accept("goals: a debt to an unreadable player id was left out");
                    }
                }
            }
        }
        boolean emptyBoard;
        try (var rows = connection.createStatement().executeQuery("SELECT COUNT(*) FROM goal")) {
            emptyBoard = rows.next() && rows.getInt(1) == 0;
        }
        long next = Math.max(GoalBook.counter(connection), Math.max(1, yaml.getLong("next-number", 1)));
        ConfigurationSection written = yaml.getConfigurationSection("goals");
        if (emptyBoard && written != null) {
            for (String key : written.getKeys(false)) {
                ConfigurationSection each = written.getConfigurationSection(key);
                if (each == null) {
                    continue;
                }
                try {
                    Map<UUID, Integer> given = new LinkedHashMap<>();
                    ConfigurationSection who = each.getConfigurationSection("given");
                    if (who != null) {
                        for (String id : who.getKeys(false)) {
                            given.put(UUID.fromString(id), Math.max(0, who.getInt(id)));
                        }
                    }
                    Goal goal = new Goal(Long.parseLong(key), each.getString("template", ""), each.getString("title", ""),
                            each.getString("icon", "CHEST"), GoalKind.valueOf(each.getString("kind", "DELIVER")),
                            ItemSelection.parse(each.getStringList("items")), Math.max(1, each.getInt("amount", 1)),
                            each.getLong("started-at"), each.getLong("ends-at"), given);
                    if (!free(connection, goal)) {
                        continue;
                    }
                    GoalBook.insert(connection, goal);
                    next = Math.max(next, goal.number() + 1);
                } catch (IllegalArgumentException unreadable) {
                    problems.accept("goals: goal " + key + " could not be read and was left out");
                }
            }
        }
        try (PreparedStatement counter = connection.prepareStatement("UPDATE jobs_counter SET next = ? WHERE id = 1")) {
            counter.setLong(1, next);
            counter.executeUpdate();
        }
    }

    private static boolean free(Connection connection, Goal goal) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT 1 FROM goal WHERE number = ? OR template = ?")) {
            query.setLong(1, goal.number());
            query.setString(2, goal.template());
            try (ResultSet rows = query.executeQuery()) {
                return !rows.next();
            }
        }
    }

    // ---------------------------------------------------------------------------- quests and orders

    private void quests(Connection connection, YamlConfiguration yaml) throws SQLException {
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        for (String id : players.getKeys(false)) {
            ConfigurationSection each = players.getConfigurationSection(id);
            if (each == null) {
                continue;
            }
            try {
                UUID player = UUID.fromString(id);
                if (has(connection, "quest_day", player)) {
                    continue;
                }
                List<Quest> quests = new ArrayList<>();
                for (Map<?, ?> written : each.getMapList("quests")) {
                    quests.add(new Quest(String.valueOf(written.get("template")), number(written.get("amount")),
                            Money.of(number(written.get("pay"))), number(written.get("progress")),
                            Quest.State.valueOf(String.valueOf(written.containsKey("state") ? written.get("state") : "OPEN"))));
                }
                QuestBook.replace(connection, player, new QuestDay(each.getString("day", ""), each.getInt("tier"), quests,
                        each.getStringList("before"), each.getInt("rerolls")), 0);
            } catch (IllegalArgumentException unreadable) {
                problems.accept("quests: the day of " + id + " could not be read and was left out");
            }
        }
    }

    private void orders(Connection connection, YamlConfiguration yaml) throws SQLException {
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        for (String id : players.getKeys(false)) {
            ConfigurationSection each = players.getConfigurationSection(id);
            if (each == null) {
                continue;
            }
            try {
                UUID player = UUID.fromString(id);
                if (has(connection, "order_ledger", player)) {
                    continue;
                }
                ConfigurationSection written = each.getConfigurationSection("order");
                Order order = written == null ? null : new Order(written.getString("work", ""),
                        written.getString("says", ""), written.getInt("units", 1), Money.of(written.getLong("pay")),
                        written.getLong("started-at"), written.getLong("ends-at"), written.getInt("progress"),
                        Order.State.valueOf(written.getString("state", "OPEN")));
                OrderBook.replace(connection, player, new OrderBook.Ledger(each.getString("day", ""), each.getInt("taken"),
                        each.getInt("rerolls"), each.getBoolean("offered"), order), 0);
            } catch (IllegalArgumentException unreadable) {
                problems.accept("orders: the ledger of " + id + " could not be read and was left out");
            }
        }
    }

    private static boolean has(Connection connection, String table, UUID player) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT 1 FROM " + table + " WHERE player = ?")) {
            query.setString(1, player.toString());
            try (ResultSet rows = query.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static int number(Object value) {
        return value instanceof Number number ? (int) Math.min(Integer.MAX_VALUE, number.longValue()) : 0;
    }
}
