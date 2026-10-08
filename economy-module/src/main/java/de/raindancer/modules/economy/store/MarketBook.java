package de.raindancer.modules.economy.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.modules.economy.rules.MarketRule;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Supply-and-demand pressure per item, kept across restarts. Stored as the pressure at a moment; read
 * decayed to now, so nothing needs a timer to wear prices back down.
 */
public final class MarketBook {

    private record Pressure(double value, long at) {
    }

    private final Database database;
    private final MarketRule rule;
    private final LongSupplier clock;
    private final Map<String, Pressure> pressures = new HashMap<>();
    private final Set<String> dirty = new HashSet<>();

    public MarketBook(Database database, MarketRule rule, LongSupplier clock) {
        this.database = database;
        this.rule = rule;
        this.clock = clock;
    }

    public void load() {
        database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement("SELECT material, pressure, updated FROM market");
                 ResultSet rows = select.executeQuery()) {
                synchronized (pressures) {
                    while (rows.next()) {
                        pressures.put(rows.getString(1), new Pressure(rows.getDouble(2), rows.getLong(3)));
                    }
                }
            }
            return true;
        });
    }

    /** The pressure on an item right now. */
    public double pressure(String material, double halfLifeHours) {
        synchronized (pressures) {
            Pressure stored = pressures.get(material);
            if (stored == null) {
                return 0;
            }
            return rule.decayed(stored.value(), clock.getAsLong() - stored.at(), halfLifeHours);
        }
    }

    /** Somebody bought or sold this many. */
    public void traded(String material, int quantity, int stackSize, double perStack, boolean buying,
                       double halfLifeHours) {
        synchronized (pressures) {
            double now = pressure(material, halfLifeHours);
            pressures.put(material, new Pressure(rule.pushed(now, quantity, stackSize, perStack, buying),
                    clock.getAsLong()));
            dirty.add(material);
        }
    }

    /** Everything back to its plain price — for an owner who changed the rules. */
    public void calm() {
        synchronized (pressures) {
            dirty.addAll(pressures.keySet());
            pressures.replaceAll((material, pressure) -> new Pressure(0, clock.getAsLong()));
        }
    }

    public Optional<Double> stored(String material) {
        synchronized (pressures) {
            return Optional.ofNullable(pressures.get(material)).map(Pressure::value);
        }
    }

    /** Off the server's threads. */
    public void flush() {
        Map<String, Pressure> writing = new HashMap<>();
        synchronized (pressures) {
            for (String material : dirty) {
                writing.put(material, pressures.get(material));
            }
            dirty.clear();
        }
        if (writing.isEmpty()) {
            return;
        }
        boolean written = database.write(connection -> {
            try (PreparedStatement upsert = connection.prepareStatement(
                    "INSERT INTO market (material, pressure, updated) VALUES (?, ?, ?) ON CONFLICT(material) "
                            + "DO UPDATE SET pressure = excluded.pressure, updated = excluded.updated")) {
                for (Map.Entry<String, Pressure> each : writing.entrySet()) {
                    upsert.setString(1, each.getKey());
                    upsert.setDouble(2, each.getValue().value());
                    upsert.setLong(3, each.getValue().at());
                    upsert.addBatch();
                }
                upsert.executeBatch();
            }
        });
        if (!written) {
            synchronized (pressures) {
                dirty.addAll(writing.keySet());
            }
        }
    }
}
