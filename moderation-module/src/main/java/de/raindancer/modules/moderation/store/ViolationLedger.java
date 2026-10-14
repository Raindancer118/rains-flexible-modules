package de.raindancer.modules.moderation.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.modules.moderation.model.CheckType;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Every player's violation levels across the network. Each server keeps judging in memory; what it adds to a level
 * (or takes off) is gathered here and written as a delta — {@code level + delta}, decayed as the checks decay —
 * so two servers adding at the same moment both count. A server a player hops to reads their levels on join.
 */
public final class ViolationLedger {

    /** A level after decay, plus the delta, never below zero — in SQL both dialects share. */
    private static final String DECAYED = "(CASE WHEN ac_violation.level - (? - ac_violation.updated_at) / 60000.0 * ? > 0 "
            + "THEN ac_violation.level - (? - ac_violation.updated_at) / 60000.0 * ? ELSE 0 END)";
    private static final String UPSERT = "INSERT INTO ac_violation (player, check_key, level, updated_at) VALUES (?, ?, ?, ?) "
            + "ON CONFLICT(player, check_key) DO UPDATE SET level = CASE WHEN " + DECAYED + " + ? > 0 THEN " + DECAYED
            + " + ? ELSE 0 END, updated_at = ?";

    private record Key(UUID player, CheckType check) {
    }

    private final Database database;
    private final LongSupplier clock;
    private final Map<Key, Double> pending = new HashMap<>();
    private final Set<UUID> clears = new HashSet<>();

    public ViolationLedger(Database database, LongSupplier clock) {
        this.database = database;
        this.clock = clock;
    }

    /** Gathers a change of {@code delta} (negative to take off) for the next {@link #flush}. */
    public synchronized void add(UUID player, CheckType check, double delta) {
        if (delta != 0) {
            pending.merge(new Key(player, check), delta, Double::sum);
        }
    }

    /** Wipes a player's levels on the network; what was gathered for them before is dropped. */
    public synchronized void clear(UUID player) {
        pending.keySet().removeIf(key -> key.player().equals(player));
        clears.add(player);
    }

    /** @return whether everything gathered reached the database; what did not is kept for the next try */
    public boolean flush() {
        Map<Key, Double> deltas;
        Set<UUID> wiped;
        synchronized (this) {
            if (pending.isEmpty() && clears.isEmpty()) {
                return true;
            }
            deltas = new HashMap<>(pending);
            wiped = new HashSet<>(clears);
            pending.clear();
            clears.clear();
        }
        long now = clock.getAsLong();
        boolean written = database.write(connection -> {
            try (PreparedStatement wipe = connection.prepareStatement("DELETE FROM ac_violation WHERE player = ?");
                 PreparedStatement upsert = connection.prepareStatement(UPSERT)) {
                for (UUID player : wiped) {
                    wipe.setString(1, player.toString());
                    wipe.executeUpdate();
                }
                for (Map.Entry<Key, Double> delta : deltas.entrySet()) {
                    double rate = delta.getKey().check().decayPerMinute();
                    int at = 1;
                    upsert.setString(at++, delta.getKey().player().toString());
                    upsert.setString(at++, delta.getKey().check().key());
                    upsert.setDouble(at++, Math.max(0, delta.getValue()));
                    upsert.setLong(at++, now);
                    at = decay(upsert, at, now, rate);
                    upsert.setDouble(at++, delta.getValue());
                    at = decay(upsert, at, now, rate);
                    upsert.setDouble(at++, delta.getValue());
                    upsert.setLong(at, now);
                    upsert.executeUpdate();
                }
            }
        });
        if (!written) {
            synchronized (this) {
                // Put back under whatever was gathered meanwhile; a clear stays ahead of the deltas after it.
                deltas.forEach((key, delta) -> pending.merge(key, delta, Double::sum));
                clears.addAll(wiped);
            }
        }
        return written;
    }

    /** What the network holds against a player now, decay applied; empty when the database cannot be read. */
    public Optional<Map<CheckType, Double>> levels(UUID player) {
        long now = clock.getAsLong();
        return database.read(connection -> {
            Map<CheckType, Double> found = new EnumMap<>(CheckType.class);
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT check_key, level, updated_at FROM ac_violation WHERE player = ?")) {
                select.setString(1, player.toString());
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        Optional<CheckType> check = CheckType.find(rows.getString(1));
                        if (check.isEmpty()) {
                            continue;
                        }
                        double minutes = Math.max(0, now - rows.getLong(3)) / 60_000.0;
                        double level = rows.getDouble(2) - minutes * check.get().decayPerMinute();
                        if (level > 1e-9) {
                            found.put(check.get(), level);
                        }
                    }
                }
            }
            return found;
        });
    }

    private static int decay(PreparedStatement statement, int at, long now, double rate) throws java.sql.SQLException {
        for (int twice = 0; twice < 2; twice++) {
            statement.setLong(at++, now);
            statement.setDouble(at++, rate);
        }
        return at;
    }
}
