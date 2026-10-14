package de.raindancer.modules.guilds.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.network.NetworkBus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shards of Rain's enchantments each guild paid in, and which enchantments that unlocked — for the network.
 *
 * <p>Paying adds to what the database holds ({@code paid + n}), so two servers paying at once both count; the
 * unlock is a change from "locked" that only one write can make, so it is announced once. Reads come from
 * memory, which every server keeps current from the network bus.
 */
public final class ShardBook {

    public static final String TOPIC = "guild-shards";

    public record Progress(int paid, boolean unlocked) {
        public static final Progress NONE = new Progress(0, false);
    }

    /** What one payment made of it. */
    public record Paid(int paid, boolean unlocked, boolean justUnlocked) {
    }

    private final Database database;
    private final Map<String, Map<String, Progress>> guilds = new ConcurrentHashMap<>();
    private volatile NetworkBus bus = NetworkBus.NONE;

    public ShardBook(Database database) {
        this.database = database;
    }

    /** @return false when it could not be read */
    public boolean load() {
        Optional<Map<String, Map<String, Progress>>> read = database.read(connection -> read(connection, null));
        read.ifPresent(all -> {
            guilds.clear();
            guilds.putAll(all);
        });
        return read.isPresent();
    }

    public NetworkBus.Subscription connect(NetworkBus network) {
        this.bus = network == null ? NetworkBus.NONE : network;
        NetworkBus.Subscription changes = bus.subscribe(TOPIC, event -> refresh(event.key()));
        NetworkBus.Subscription resync = bus.onResync(this::load);
        return () -> {
            changes.close();
            resync.close();
        };
    }

    private void refresh(String guild) {
        database.read(connection -> read(connection, guild)).ifPresent(read ->
                guilds.put(guild, read.getOrDefault(guild, new ConcurrentHashMap<>())));
    }

    private static Map<String, Map<String, Progress>> read(Connection connection, String only) throws SQLException {
        Map<String, Map<String, Progress>> all = new HashMap<>();
        String sql = "SELECT guild, enchantment, paid, unlocked FROM guild_shard" + (only == null ? "" : " WHERE guild = ?");
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            if (only != null) {
                query.setString(1, only);
            }
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    all.computeIfAbsent(rows.getString(1), id -> new ConcurrentHashMap<>())
                            .put(rows.getString(2), new Progress(rows.getInt(3), rows.getInt(4) != 0));
                }
            }
        }
        return all;
    }

    public Progress progress(String guild, String enchantment) {
        return guilds.getOrDefault(guild, Map.of()).getOrDefault(enchantment, Progress.NONE);
    }

    public boolean unlocked(String guild, String enchantment) {
        return progress(guild, enchantment).unlocked();
    }

    /**
     * Adds shards to what the guild paid. Writes the database, so off the server's threads.
     *
     * @return what it came to; empty when the database could not be written (nothing was counted)
     */
    public Optional<Paid> pay(String guild, String enchantment, int amount, int threshold) {
        Paid[] result = new Paid[1];
        boolean written = database.write(connection -> {
            try (PreparedStatement add = connection.prepareStatement("""
                    INSERT INTO guild_shard (guild, enchantment, paid, unlocked) VALUES (?, ?, ?, 0)
                    ON CONFLICT (guild, enchantment) DO UPDATE SET paid = guild_shard.paid + excluded.paid""")) {
                add.setString(1, guild);
                add.setString(2, enchantment);
                add.setInt(3, Math.max(0, amount));
                add.executeUpdate();
            }
            int paid;
            boolean unlocked;
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT paid, unlocked FROM guild_shard WHERE guild = ? AND enchantment = ?")) {
                query.setString(1, guild);
                query.setString(2, enchantment);
                try (ResultSet rows = query.executeQuery()) {
                    rows.next();
                    paid = rows.getInt(1);
                    unlocked = rows.getInt(2) != 0;
                }
            }
            boolean now = false;
            if (!unlocked && paid >= threshold) {
                try (PreparedStatement unlock = connection.prepareStatement(
                        "UPDATE guild_shard SET unlocked = 1 WHERE guild = ? AND enchantment = ? AND unlocked = 0")) {
                    unlock.setString(1, guild);
                    unlock.setString(2, enchantment);
                    now = unlock.executeUpdate() == 1;
                }
            }
            result[0] = new Paid(paid, unlocked || now, now);
        });
        if (!written || result[0] == null) {
            return Optional.empty();
        }
        guilds.computeIfAbsent(guild, id -> new ConcurrentHashMap<>())
                .put(enchantment, new Progress(result[0].paid(), result[0].unlocked()));
        bus.publish(TOPIC, guild);
        return Optional.of(result[0]);
    }

    /** A disbanded guild's progress goes with it. */
    public void forget(String guild) {
        database.write(connection -> {
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM guild_shard WHERE guild = ?")) {
                delete.setString(1, guild);
                delete.executeUpdate();
            }
        });
        guilds.remove(guild);
        bus.publish(TOPIC, guild);
    }
}
