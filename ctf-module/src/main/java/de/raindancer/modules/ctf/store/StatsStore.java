package de.raindancer.modules.ctf.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.modules.ctf.model.Stats;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Records in the database. A round's result is added in one statement per player ({@code wins = stats.wins + …}), so two
 * writes at once cannot both read the old numbers. Never asked on the server thread.
 */
public final class StatsStore {

    /** What a leaderboard sorts by. */
    public enum Board {
        WINS("wins"), CAPTURES("captures"), KILLS("kills"), XP("xp");

        private final String column;

        Board(String column) {
            this.column = column;
        }
    }

    /** One round's additions for one player. */
    public record Delta(UUID player, String name, boolean won, boolean lost, int captures, int returns, int kills,
                        int deaths, int carrierKills, boolean mvp, long fastestCapture, int xp) {
    }

    /** One line of the weekly board. */
    public record Weekly(UUID player, String name, int wins, int captures, int kills) {
    }

    private final Database database;

    public StatsStore(Database database) {
        this.database = database;
    }

    public Database database() {
        return database;
    }

    public boolean add(Delta delta, long week) {
        return database.write(connection -> {
            try (PreparedStatement row = connection.prepareStatement(
                    "INSERT INTO stats (player, name, rounds, wins, losses, captures, returns, kills, deaths, "
                            + "carrier_kills, mvps, fastest_capture, xp) VALUES (?,?,1,?,?,?,?,?,?,?,?,?,?) "
                            + "ON CONFLICT(player) DO UPDATE SET name = excluded.name, rounds = stats.rounds + 1, "
                            + "wins = stats.wins + excluded.wins, losses = stats.losses + excluded.losses, "
                            + "captures = stats.captures + excluded.captures, returns = stats.returns + excluded.returns, "
                            + "kills = stats.kills + excluded.kills, deaths = stats.deaths + excluded.deaths, "
                            + "carrier_kills = stats.carrier_kills + excluded.carrier_kills, "
                            + "mvps = stats.mvps + excluded.mvps, "
                            + "fastest_capture = CASE WHEN excluded.fastest_capture > 0 AND (stats.fastest_capture = 0 "
                            + "OR excluded.fastest_capture < stats.fastest_capture) THEN excluded.fastest_capture "
                            + "ELSE stats.fastest_capture END, xp = stats.xp + excluded.xp")) {
                row.setString(1, delta.player().toString());
                row.setString(2, delta.name());
                row.setInt(3, delta.won() ? 1 : 0);
                row.setInt(4, delta.lost() ? 1 : 0);
                row.setInt(5, delta.captures());
                row.setInt(6, delta.returns());
                row.setInt(7, delta.kills());
                row.setInt(8, delta.deaths());
                row.setInt(9, delta.carrierKills());
                row.setInt(10, delta.mvp() ? 1 : 0);
                row.setLong(11, Math.max(0, delta.fastestCapture()));
                row.setLong(12, delta.xp());
                row.executeUpdate();
            }
            try (PreparedStatement row = connection.prepareStatement(
                    "INSERT INTO weekly (player, week, name, wins, captures, kills) VALUES (?,?,?,?,?,?) "
                            + "ON CONFLICT(player, week) DO UPDATE SET name = excluded.name, "
                            + "wins = weekly.wins + excluded.wins, captures = weekly.captures + excluded.captures, "
                            + "kills = weekly.kills + excluded.kills")) {
                row.setString(1, delta.player().toString());
                row.setLong(2, week);
                row.setString(3, delta.name());
                row.setInt(4, delta.won() ? 1 : 0);
                row.setInt(5, delta.captures());
                row.setInt(6, delta.kills());
                row.executeUpdate();
            }
        });
    }

    /** Experience only — a challenge done, for one. */
    public boolean addXp(UUID player, String name, int xp) {
        return database.write(connection -> {
            try (PreparedStatement row = connection.prepareStatement(
                    "INSERT INTO stats (player, name, xp) VALUES (?,?,?) ON CONFLICT(player) DO UPDATE SET "
                            + "name = excluded.name, xp = stats.xp + excluded.xp")) {
                row.setString(1, player.toString());
                row.setString(2, name);
                row.setLong(3, xp);
                row.executeUpdate();
            }
        });
    }

    public Optional<Stats> of(UUID player) {
        return database.read(connection -> {
            try (PreparedStatement query = connection.prepareStatement(SELECT + " WHERE player = ?")) {
                query.setString(1, player.toString());
                try (ResultSet rows = query.executeQuery()) {
                    return rows.next() ? read(rows) : null;
                }
            }
        });
    }

    public List<Stats> top(Board board, int count) {
        return database.read(connection -> {
            List<Stats> top = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement(SELECT + " WHERE " + board.column + " > 0 ORDER BY "
                    + board.column + " DESC, name ASC LIMIT ?")) {
                query.setInt(1, Math.max(1, count));
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        top.add(read(rows));
                    }
                }
            }
            return top;
        }).orElse(List.of());
    }

    public List<Weekly> week(long week, int count) {
        return database.read(connection -> {
            List<Weekly> top = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT player, name, wins, captures, kills FROM weekly WHERE week = ? AND (captures > 0 OR "
                            + "wins > 0) ORDER BY captures DESC, wins DESC, name ASC LIMIT ?")) {
                query.setLong(1, week);
                query.setInt(2, Math.max(1, count));
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        top.add(new Weekly(UUID.fromString(rows.getString(1)), rows.getString(2), rows.getInt(3),
                                rows.getInt(4), rows.getInt(5)));
                    }
                }
            }
            return top;
        }).orElse(List.of());
    }

    private static final String SELECT = "SELECT player, name, rounds, wins, losses, captures, returns, kills, deaths, "
            + "carrier_kills, mvps, fastest_capture, xp FROM stats";

    private static Stats read(ResultSet rows) throws SQLException {
        return new Stats(UUID.fromString(rows.getString(1)), rows.getString(2), rows.getInt(3), rows.getInt(4),
                rows.getInt(5), rows.getInt(6), rows.getInt(7), rows.getInt(8), rows.getInt(9), rows.getInt(10),
                rows.getInt(11), rows.getLong(12), (int) Math.min(Integer.MAX_VALUE, rows.getLong(13)));
    }
}
