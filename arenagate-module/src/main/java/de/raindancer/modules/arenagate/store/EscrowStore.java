package de.raindancer.modules.arenagate.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.modules.arenagate.model.Held;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Held stakes in SQLite. A row is HELD from the moment the money left the player until the fight is settled, then PAID
 * or REFUNDED — and only a HELD row can be settled, which is what makes asking twice harmless.
 */
public final class EscrowStore {

    private final Database database;

    public EscrowStore(Database database) {
        this.database = database;
    }

    public boolean hold(String fight, UUID player, long amount, long now) {
        return database.write(connection -> {
            try (PreparedStatement row = connection.prepareStatement(
                    "INSERT INTO escrow (fight, player, amount, state, created) VALUES (?,?,?,'HELD',?)")) {
                row.setString(1, fight);
                row.setString(2, player.toString());
                row.setLong(3, amount);
                row.setLong(4, now);
                row.executeUpdate();
            }
        });
    }

    public List<Held> held(String fight) {
        return query("SELECT fight, player, amount, created FROM escrow WHERE fight = ? AND state = 'HELD'", fight, 0);
    }

    public List<Held> heldBefore(long time) {
        return query("SELECT fight, player, amount, created FROM escrow WHERE state = 'HELD' AND created < ?", null, time);
    }

    /** @return whether the row was still HELD — and so is now settled by this call, and by no other */
    public boolean settle(String fight, UUID player, String state, long now) {
        int[] changed = {0};
        database.write(connection -> {
            try (PreparedStatement row = connection.prepareStatement(
                    "UPDATE escrow SET state = ?, settled = ? WHERE fight = ? AND player = ? AND state = 'HELD'")) {
                row.setString(1, state);
                row.setLong(2, now);
                row.setString(3, fight);
                row.setString(4, player.toString());
                changed[0] = row.executeUpdate();
            }
        });
        return changed[0] == 1;
    }

    private List<Held> query(String sql, String fight, long time) {
        return database.read(connection -> {
            List<Held> rows = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement(sql)) {
                if (fight != null) {
                    query.setString(1, fight);
                } else {
                    query.setLong(1, time);
                }
                try (ResultSet found = query.executeQuery()) {
                    while (found.next()) {
                        rows.add(new Held(found.getString(1), UUID.fromString(found.getString(2)), found.getLong(3),
                                found.getLong(4)));
                    }
                }
            }
            return rows;
        }).orElse(List.of());
    }
}
