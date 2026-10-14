package de.raindancer.modules.arena.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.modules.arena.model.Stats;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Stats in the database. Each fight's result is added in one statement per player — {@code wins = wins + 1} — so two fights
 * ending at once cannot both read the old numbers and one of them be lost.
 */
public final class StatsStore {

    private final Database database;

    public StatsStore(Database database) {
        this.database = database;
    }

    public boolean add(UUID player, String name, int wins, int losses, int draws, int kills, int deaths, int wave) {
        return database.write(connection -> {
            try (PreparedStatement row = connection.prepareStatement(
                    "INSERT INTO stats (player, name, wins, losses, draws, kills, deaths, best_wave) "
                            + "VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(player) DO UPDATE SET name = excluded.name, "
                            + "wins = stats.wins + excluded.wins, losses = stats.losses + excluded.losses, "
                            + "draws = stats.draws + excluded.draws, kills = stats.kills + excluded.kills, "
                            + "deaths = stats.deaths + excluded.deaths, best_wave = CASE WHEN stats.best_wave > "
                            + "excluded.best_wave THEN stats.best_wave ELSE excluded.best_wave END")) {
                row.setString(1, player.toString());
                row.setString(2, name);
                row.setInt(3, wins);
                row.setInt(4, losses);
                row.setInt(5, draws);
                row.setInt(6, kills);
                row.setInt(7, deaths);
                row.setInt(8, wave);
                row.executeUpdate();
            }
        });
    }

    public Optional<Stats> of(UUID player) {
        return database.read(connection -> {
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT player, name, wins, losses, draws, kills, deaths, best_wave FROM stats WHERE player = ?")) {
                query.setString(1, player.toString());
                try (ResultSet rows = query.executeQuery()) {
                    return rows.next() ? read(rows) : null;
                }
            }
        });
    }

    /** The best by wins (or by best wave), most first. */
    public List<Stats> top(boolean byWave, int count) {
        return database.read(connection -> {
            List<Stats> top = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT player, name, wins, losses, draws, kills, deaths, best_wave FROM stats WHERE "
                            + (byWave ? "best_wave > 0 ORDER BY best_wave DESC, kills DESC" : "wins > 0 ORDER BY wins DESC, kills DESC")
                            + " LIMIT ?")) {
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

    private static Stats read(ResultSet rows) throws java.sql.SQLException {
        return new Stats(UUID.fromString(rows.getString(1)), rows.getString(2), rows.getInt(3), rows.getInt(4),
                rows.getInt(5), rows.getInt(6), rows.getInt(7), rows.getInt(8));
    }
}
