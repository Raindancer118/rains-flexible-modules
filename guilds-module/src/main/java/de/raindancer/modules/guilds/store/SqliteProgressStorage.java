package de.raindancer.modules.guilds.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.modules.guilds.model.Boost;
import de.raindancer.modules.guilds.model.Contract;
import de.raindancer.modules.guilds.model.Perk;
import de.raindancer.modules.guilds.model.Standing;
import de.raindancer.modules.guilds.model.XpSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** The progress tables of guilds.db. */
public final class SqliteProgressStorage implements ProgressStorage {

    private final Database database;

    public SqliteProgressStorage(Database database) {
        this.database = database;
    }

    @Override
    public Optional<Loaded> load() {
        if (!database.isUsable()) {
            return Optional.empty();
        }
        return database.read(connection -> {
            Map<String, Map<XpSource, Long>> totals = new HashMap<>();
            try (var rows = connection.createStatement().executeQuery("SELECT * FROM guild_xp_source")) {
                while (rows.next()) {
                    String guild = rows.getString("guild");
                    long xp = rows.getLong("xp");
                    XpSource.named(rows.getString("source")).ifPresent(source ->
                            totals.computeIfAbsent(guild, key -> new EnumMap<>(XpSource.class)).put(source, xp));
                }
            }
            List<Contract> contracts = new ArrayList<>();
            try (var rows = connection.createStatement().executeQuery("SELECT * FROM contract")) {
                while (rows.next()) {
                    Optional<XpSource> source = XpSource.named(rows.getString("source"));
                    if (source.isEmpty()) {
                        continue;
                    }
                    contracts.add(new Contract(rows.getString("guild"), rows.getLong("week"), rows.getInt("slot"),
                            source.get(), rows.getString("what"), rows.getString("title"), rows.getLong("goal"),
                            rows.getLong("progress"), rows.getLong("reward"), rows.getInt("done") != 0));
                }
            }
            Boost boost = Boost.NONE;
            try (var rows = connection.createStatement().executeQuery("SELECT * FROM boost WHERE id = 1")) {
                if (rows.next()) {
                    String by = rows.getString("started_by");
                    Optional<UUID> who;
                    try {
                        who = by == null ? Optional.empty() : Optional.of(UUID.fromString(by));
                    } catch (IllegalArgumentException unreadable) {
                        who = Optional.empty();
                    }
                    boost = new Boost(rows.getInt("percent"), rows.getLong("until"), who);
                }
            }
            Map<String, Map<Perk, Integer>> perks = new HashMap<>();
            try (var rows = connection.createStatement().executeQuery("SELECT * FROM guild_perk")) {
                while (rows.next()) {
                    String guild = rows.getString("guild");
                    int rank = rows.getInt("rank");
                    Perk.named(rows.getString("perk")).ifPresent(perk ->
                            perks.computeIfAbsent(guild, key -> new EnumMap<>(Perk.class)).put(perk, rank));
                }
            }
            Map<String, Standing> standings = new HashMap<>();
            try (var rows = connection.createStatement().executeQuery("SELECT * FROM guild_standing")) {
                while (rows.next()) {
                    String guild = rows.getString("guild");
                    standings.put(guild, new Standing(rows.getInt("level_points"), rows.getInt("prestige"),
                            rows.getInt("crowns"), perks.getOrDefault(guild, Map.of()), rows.getLong("respec_at")));
                }
            }
            Map<String, Map<Long, Long>> weekXp = new HashMap<>();
            try (var rows = connection.createStatement().executeQuery("SELECT * FROM week_xp")) {
                while (rows.next()) {
                    weekXp.computeIfAbsent(rows.getString("guild"), key -> new HashMap<>())
                            .put(rows.getLong("week"), rows.getLong("xp"));
                }
            }
            CrownState crown = CrownState.NONE;
            try (var rows = connection.createStatement().executeQuery("SELECT * FROM crown WHERE id = 1")) {
                if (rows.next()) {
                    crown = new CrownState(rows.getString("guild"), rows.getLong("week"), rows.getLong("settled_week"));
                }
            }
            return new Loaded(totals, contracts, boost, standings, weekXp, crown);
        });
    }

    @Override
    public boolean write(Map<String, Map<XpSource, Long>> totals, Map<String, List<Contract>> contracts,
                         Set<String> forgotten, Optional<Boost> boost) {
        return database.write(connection -> {
            for (String guild : forgotten) {
                delete(connection, "guild_xp_source", guild);
                delete(connection, "contract", guild);
            }
            for (Map.Entry<String, Map<XpSource, Long>> each : totals.entrySet()) {
                delete(connection, "guild_xp_source", each.getKey());
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO guild_xp_source (guild, source, xp) VALUES (?,?,?)")) {
                    for (Map.Entry<XpSource, Long> total : each.getValue().entrySet()) {
                        insert.setString(1, each.getKey());
                        insert.setString(2, total.getKey().name());
                        insert.setLong(3, total.getValue());
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
            }
            for (Map.Entry<String, List<Contract>> each : contracts.entrySet()) {
                delete(connection, "contract", each.getKey());
                try (PreparedStatement insert = connection.prepareStatement("INSERT INTO contract (guild, week, slot, "
                        + "source, what, title, goal, progress, reward, done) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
                    for (Contract contract : each.getValue()) {
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
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
            }
            if (boost.isPresent()) {
                try (PreparedStatement upsert = connection.prepareStatement(
                        "INSERT OR REPLACE INTO boost (id, percent, until, started_by) VALUES (1,?,?,?)")) {
                    upsert.setInt(1, boost.get().percent());
                    upsert.setLong(2, boost.get().until());
                    if (boost.get().by().isPresent()) {
                        upsert.setString(3, boost.get().by().get().toString());
                    } else {
                        upsert.setNull(3, Types.VARCHAR);
                    }
                    upsert.executeUpdate();
                }
            }
        });
    }

    @Override
    public boolean writeHonours(Map<String, Standing> standings, Map<String, Map<Long, Long>> weekXp,
                                Set<String> forgotten, Optional<CrownState> crown) {
        return database.write(connection -> {
            for (String guild : forgotten) {
                delete(connection, "guild_standing", guild);
                delete(connection, "guild_perk", guild);
                delete(connection, "week_xp", guild);
            }
            for (Map.Entry<String, Standing> each : standings.entrySet()) {
                delete(connection, "guild_standing", each.getKey());
                delete(connection, "guild_perk", each.getKey());
                Standing standing = each.getValue();
                try (PreparedStatement insert = connection.prepareStatement("INSERT INTO guild_standing (guild, "
                        + "level_points, prestige, crowns, respec_at) VALUES (?,?,?,?,?)")) {
                    insert.setString(1, each.getKey());
                    insert.setInt(2, standing.levelPoints());
                    insert.setInt(3, standing.prestige());
                    insert.setInt(4, standing.crowns());
                    insert.setLong(5, standing.respecAt());
                    insert.executeUpdate();
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO guild_perk (guild, perk, rank) VALUES (?,?,?)")) {
                    for (Map.Entry<Perk, Integer> perk : standing.perks().entrySet()) {
                        insert.setString(1, each.getKey());
                        insert.setString(2, perk.getKey().key());
                        insert.setInt(3, perk.getValue());
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
            }
            for (Map.Entry<String, Map<Long, Long>> each : weekXp.entrySet()) {
                delete(connection, "week_xp", each.getKey());
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO week_xp (guild, week, xp) VALUES (?,?,?)")) {
                    for (Map.Entry<Long, Long> week : each.getValue().entrySet()) {
                        insert.setString(1, each.getKey());
                        insert.setLong(2, week.getKey());
                        insert.setLong(3, week.getValue());
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
            }
            if (crown.isPresent()) {
                try (PreparedStatement upsert = connection.prepareStatement(
                        "INSERT OR REPLACE INTO crown (id, guild, week, settled_week) VALUES (1,?,?,?)")) {
                    upsert.setString(1, crown.get().guild());
                    upsert.setLong(2, crown.get().week());
                    upsert.setLong(3, crown.get().settledWeek());
                    upsert.executeUpdate();
                }
            }
        });
    }

    private static void delete(Connection connection, String table, String guild) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM " + table + " WHERE guild = ?")) {
            delete.setString(1, guild);
            delete.executeUpdate();
        }
    }
}
