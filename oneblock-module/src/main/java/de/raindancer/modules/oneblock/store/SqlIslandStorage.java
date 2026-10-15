package de.raindancer.modules.oneblock.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.modules.oneblock.model.Island;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Islands in the shared database (SQLite on a server of its own, PostgreSQL on a network). */
public final class SqlIslandStorage implements IslandStorage {

    private static final String ACTIVE = "active";
    private static final String ARCHIVED = "archived";

    private final Database database;

    public SqlIslandStorage(Database database) {
        this.database = database;
    }

    @Override
    public Optional<List<Island>> loadAll() {
        return database.read(connection -> {
            List<Island> islands = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement("SELECT data FROM island WHERE state = ?")) {
                query.setString(1, ACTIVE);
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        IslandCodec.read(rows.getString(1)).ifPresent(islands::add);
                    }
                }
            }
            return islands;
        });
    }

    @Override
    public boolean save(Island island) {
        return write(island, ACTIVE);
    }

    @Override
    public boolean archive(Island island) {
        return write(island, ARCHIVED);
    }

    private boolean write(Island island, String state) {
        String id = island.id().toString();
        return database.write(connection -> {
            try (PreparedStatement row = connection.prepareStatement(
                    "INSERT INTO island (id, owner, mode, state, data, updated) VALUES (?,?,?,?,?,?) "
                            + "ON CONFLICT(id) DO UPDATE SET owner = excluded.owner, mode = excluded.mode, "
                            + "state = excluded.state, data = excluded.data, updated = excluded.updated")) {
                row.setString(1, id);
                row.setString(2, island.owner().toString());
                row.setString(3, island.mode().wire());
                row.setString(4, state);
                row.setString(5, IslandCodec.write(island));
                row.setLong(6, System.currentTimeMillis());
                row.executeUpdate();
            }
            try (PreparedStatement clear = connection.prepareStatement("DELETE FROM island_member WHERE island = ?")) {
                clear.setString(1, id);
                clear.executeUpdate();
            }
            if (!ACTIVE.equals(state)) {
                return;
            }
            try (PreparedStatement member = connection.prepareStatement(
                    "INSERT INTO island_member (player, island) VALUES (?,?) "
                            + "ON CONFLICT(player) DO UPDATE SET island = excluded.island")) {
                for (UUID player : island.everyone()) {
                    member.setString(1, player.toString());
                    member.setString(2, id);
                    member.addBatch();
                }
                member.executeBatch();
            }
        });
    }

    @Override
    public boolean savePosition(UUID player, PlayerSpot spot, long now) {
        return database.write(connection -> {
            try (PreparedStatement row = connection.prepareStatement(
                    "INSERT INTO island_position (player, world, x, y, z, yaw, pitch, updated) VALUES (?,?,?,?,?,?,?,?) "
                            + "ON CONFLICT(player) DO UPDATE SET world = excluded.world, x = excluded.x, y = excluded.y, "
                            + "z = excluded.z, yaw = excluded.yaw, pitch = excluded.pitch, updated = excluded.updated")) {
                row.setString(1, player.toString());
                row.setString(2, spot.world());
                row.setDouble(3, spot.x());
                row.setDouble(4, spot.y());
                row.setDouble(5, spot.z());
                row.setDouble(6, spot.yaw());
                row.setDouble(7, spot.pitch());
                row.setLong(8, now);
                row.executeUpdate();
            }
        });
    }

    @Override
    public Optional<PlayerSpot> position(UUID player) {
        return database.read(connection -> {
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT world, x, y, z, yaw, pitch FROM island_position WHERE player = ?")) {
                query.setString(1, player.toString());
                try (ResultSet rows = query.executeQuery()) {
                    return rows.next() ? new PlayerSpot(rows.getString(1), rows.getDouble(2), rows.getDouble(3),
                            rows.getDouble(4), rows.getFloat(5), rows.getFloat(6)) : null;
                }
            }
        });
    }

    @Override
    public Optional<String> modeOf(UUID player) {
        return database.read(connection -> {
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT island.mode FROM island_member JOIN island ON island.id = island_member.island "
                            + "WHERE island_member.player = ? AND island.state = ?")) {
                query.setString(1, player.toString());
                query.setString(2, ACTIVE);
                try (ResultSet rows = query.executeQuery()) {
                    return rows.next() ? rows.getString(1) : null;
                }
            }
        });
    }
}
