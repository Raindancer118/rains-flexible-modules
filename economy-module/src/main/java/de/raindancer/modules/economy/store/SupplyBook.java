package de.raindancer.modules.economy.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.Fund;
import de.raindancer.modules.economy.rules.StabilizerRule.Taps;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * What the anti-inflation tools keep in {@code economy.db}: a row a day of money and prices, the stabiliser's
 * taps, community funds and season points. Every method talks to the database: off the server's threads.
 */
public final class SupplyBook {

    /** The stabiliser's taps and the epoch day they were last turned. */
    public record Stabilized(Taps taps, long day) {
    }

    private final Database database;

    public SupplyBook(Database database) {
        this.database = database;
    }

    /** The basket's price by epoch day, from {@code fromDay} on. */
    public Map<Long, Long> basketDays(long fromDay) {
        return database.read(connection -> {
            Map<Long, Long> days = new TreeMap<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT day, basket FROM supply_day WHERE day >= ?")) {
                select.setLong(1, fromDay);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        days.put(rows.getLong(1), rows.getLong(2));
                    }
                }
            }
            return days;
        }).orElse(Map.of());
    }

    /** The first day the basket was priced, and its price — the base the index is measured from. */
    public Optional<Map.Entry<Long, Long>> firstBasket() {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT day, basket FROM supply_day WHERE basket > 0 ORDER BY day LIMIT 1");
                 ResultSet rows = select.executeQuery()) {
                return rows.next() ? Optional.of(Map.entry(rows.getLong(1), rows.getLong(2)))
                        : Optional.<Map.Entry<Long, Long>>empty();
            }
        }).flatMap(found -> found);
    }

    /** Money in circulation by epoch day, from {@code fromDay} on. */
    public Map<Long, Long> circulatingDays(long fromDay) {
        return database.read(connection -> {
            Map<Long, Long> days = new TreeMap<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT day, circulating FROM supply_day WHERE day >= ?")) {
                select.setLong(1, fromDay);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        days.put(rows.getLong(1), rows.getLong(2));
                    }
                }
            }
            return days;
        }).orElse(Map.of());
    }

    public boolean recordDay(long day, Money circulating, Money cap, long basket) {
        return database.write(connection -> {
            try (PreparedStatement upsert = connection.prepareStatement(
                    "INSERT INTO supply_day (day, circulating, cap, basket) VALUES (?, ?, ?, ?) "
                            + "ON CONFLICT(day) DO UPDATE SET circulating = excluded.circulating, cap = excluded.cap, "
                            + "basket = excluded.basket")) {
                upsert.setLong(1, day);
                upsert.setLong(2, circulating.minor());
                upsert.setLong(3, cap.minor());
                upsert.setLong(4, basket);
                upsert.executeUpdate();
            }
        });
    }

    public Stabilized stabilizer() {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT faucet, sink, day FROM stabilizer WHERE id = 1");
                 ResultSet rows = select.executeQuery()) {
                return rows.next() ? new Stabilized(new Taps(rows.getInt(1), rows.getInt(2)), rows.getLong(3))
                        : new Stabilized(Taps.NEUTRAL, Long.MIN_VALUE);
            }
        }).orElse(new Stabilized(Taps.NEUTRAL, Long.MIN_VALUE));
    }

    public boolean saveStabilizer(Taps taps, long day) {
        return database.write(connection -> {
            try (PreparedStatement upsert = connection.prepareStatement(
                    "INSERT INTO stabilizer (id, faucet, sink, day) VALUES (1, ?, ?, ?) ON CONFLICT(id) DO UPDATE SET "
                            + "faucet = excluded.faucet, sink = excluded.sink, day = excluded.day")) {
                upsert.setInt(1, taps.faucet());
                upsert.setInt(2, taps.sink());
                upsert.setLong(3, day);
                upsert.executeUpdate();
            }
        });
    }

    public List<Fund> funds() {
        return database.read(connection -> {
            List<Fund> funds = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT id, name, target, raised, effect, created, done_at FROM fund ORDER BY created");
                 ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    funds.add(new Fund(UUID.fromString(rows.getString(1)), rows.getString(2), Money.of(rows.getLong(3)),
                            Money.of(rows.getLong(4)), rows.getString(5), rows.getLong(6), rows.getLong(7)));
                }
            }
            return funds;
        }).orElse(List.of());
    }

    public boolean saveFund(Fund fund) {
        return database.write(connection -> {
            try (PreparedStatement upsert = connection.prepareStatement(
                    "INSERT INTO fund (id, name, target, raised, effect, created, done_at) VALUES (?, ?, ?, ?, ?, ?, ?) "
                            + "ON CONFLICT(id) DO UPDATE SET name = excluded.name, target = excluded.target, "
                            + "raised = excluded.raised, effect = excluded.effect, done_at = excluded.done_at")) {
                upsert.setString(1, fund.id().toString());
                upsert.setString(2, fund.name());
                upsert.setLong(3, fund.target().minor());
                upsert.setLong(4, fund.raised().minor());
                upsert.setString(5, fund.effect());
                upsert.setLong(6, fund.created());
                upsert.setLong(7, fund.doneAt());
                upsert.executeUpdate();
            }
        });
    }

    /** Takes a fund off the list; its donations stay on everybody's statements. */
    public boolean removeFund(UUID id) {
        return database.write(connection -> {
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM fund WHERE id = ?")) {
                delete.setString(1, id.toString());
                delete.executeUpdate();
            }
        });
    }

    /** The season being played; 1 until the first one ends. */
    public int season() {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement("SELECT number FROM season WHERE id = 1");
                 ResultSet rows = select.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 1;
            }
        }).orElse(1);
    }

    /** Writes the points every account scored in {@code ended} and starts the next season, in one transaction. */
    public boolean endSeason(int ended, Map<UUID, Long> points) {
        return database.write(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO season_points (account, season, points) VALUES (?, ?, ?) ON CONFLICT(account, season) "
                            + "DO UPDATE SET points = excluded.points")) {
                for (Map.Entry<UUID, Long> each : points.entrySet()) {
                    insert.setString(1, each.getKey().toString());
                    insert.setInt(2, ended);
                    insert.setLong(3, each.getValue());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            try (PreparedStatement upsert = connection.prepareStatement(
                    "INSERT INTO season (id, number) VALUES (1, ?) ON CONFLICT(id) DO UPDATE SET number = excluded.number")) {
                upsert.setInt(1, ended + 1);
                upsert.executeUpdate();
            }
        });
    }

    /** Points by season for one account, oldest season first. */
    public Map<Integer, Long> pointsOf(UUID id) {
        return database.read(connection -> {
            Map<Integer, Long> points = new TreeMap<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT season, points FROM season_points WHERE account = ?")) {
                select.setString(1, id.toString());
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        points.put(rows.getInt(1), rows.getLong(2));
                    }
                }
            }
            return points;
        }).orElse(new HashMap<>());
    }
}
