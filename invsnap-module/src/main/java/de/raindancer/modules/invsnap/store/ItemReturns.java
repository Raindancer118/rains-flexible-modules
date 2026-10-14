package de.raindancer.modules.invsnap.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.sql.Schema;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.network.NetworkBus;
import org.bukkit.configuration.ConfigurationSection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Items waiting to go back to their owners — one list for the whole network, in the database.
 *
 * <p>An item here is its only copy, and its owner may come back on any server, so taking it off the list is the
 * one step that has to happen exactly once: {@link #claim} deletes the rows it names and answers with the rows it
 * actually deleted, so of two servers claiming the same item only one gets it. What is shown (counts, the menu)
 * is read from memory, kept current by the network bus; what is handed over is always decided by the database.
 */
public final class ItemReturns {

    public static final String NAME = "invsnap";
    public static final String TOPIC = "invsnap-returns";

    public static final Schema SCHEMA = Schema.of("""
            CREATE TABLE item_return (
                id       INTEGER PRIMARY KEY AUTOINCREMENT,
                owner    TEXT    NOT NULL,
                item     TEXT    NOT NULL,
                added_at INTEGER NOT NULL
            )""",
            "CREATE INDEX item_return_owner ON item_return (owner)");

    private static final LogChannel log = Log.of("invsnap");

    /** One item on the list: its row, and the item as it was encoded. */
    public record Waiting(long id, String item) {
    }

    private final Database database;
    private final LongSupplier clock;
    private final Map<UUID, List<Waiting>> shown = new ConcurrentHashMap<>();
    private volatile NetworkBus bus = NetworkBus.NONE;
    private volatile boolean readable = true;

    public ItemReturns(Database database, LongSupplier clock) {
        this.database = database;
        this.clock = clock;
    }

    /** Reads the whole list into memory. */
    public void load() {
        readable = database.isUsable() && refreshAll();
    }

    public boolean readable() {
        return readable;
    }

    /** Hears other servers' changes: an owner's list is read again when one announces it. Close to stop. */
    public NetworkBus.Subscription connect(NetworkBus network) {
        this.bus = network == null ? NetworkBus.NONE : network;
        NetworkBus.Subscription changes = bus.subscribe(TOPIC, event -> {
            try {
                refresh(UUID.fromString(event.key()));
            } catch (IllegalArgumentException notAnOwner) {
                log.warn("A returns event named no owner: '{}'", event.key());
            }
        });
        NetworkBus.Subscription resync = bus.onResync(this::refreshAll);
        return () -> {
            changes.close();
            resync.close();
        };
    }

    /** What is waiting for this owner as far as this server knows, oldest first. */
    public List<Waiting> of(UUID owner) {
        return shown.getOrDefault(owner, List.of());
    }

    /** What is waiting for this owner in the database right now; empty when it cannot be read. */
    public Optional<List<Waiting>> fresh(UUID owner) {
        return refresh(owner);
    }

    /** Puts an item on the list; false means it is not on it. */
    public boolean add(UUID owner, String item) {
        boolean written = database.write(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO item_return (owner, item, added_at) VALUES (?, ?, ?)")) {
                insert.setString(1, owner.toString());
                insert.setString(2, item);
                insert.setLong(3, clock.getAsLong());
                insert.executeUpdate();
            }
        });
        if (written) {
            changed(owner);
        }
        return written;
    }

    /**
     * Takes these rows off the owner's list, once: a row another server took first is not in the answer.
     *
     * @return the rows this call took; empty when the database could not be written, in which case nothing was
     */
    public Optional<List<Waiting>> claim(UUID owner, Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Optional.of(List.of());
        }
        List<Waiting> taken = new ArrayList<>();
        boolean ran = database.write(connection -> {
            taken.clear();
            String marks = String.join(", ", java.util.Collections.nCopies(ids.size(), "?"));
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM item_return WHERE owner = ? AND id IN (" + marks + ") RETURNING id, item")) {
                delete.setString(1, owner.toString());
                int at = 2;
                for (long id : ids) {
                    delete.setLong(at++, id);
                }
                try (ResultSet rows = delete.executeQuery()) {
                    while (rows.next()) {
                        taken.add(new Waiting(rows.getLong(1), rows.getString(2)));
                    }
                }
            }
        });
        if (!ran) {
            return Optional.empty();
        }
        if (!taken.isEmpty()) {
            changed(owner);
        }
        taken.sort(java.util.Comparator.comparingLong(Waiting::id));
        return Optional.of(List.copyOf(taken));
    }

    /**
     * Brings in what a 1.x server kept in its own {@code item-returns.yml}: every item goes onto the list, and
     * only once they are there is the file's list cleared.
     */
    public void importFrom(YamlStore legacy) {
        Optional<org.bukkit.configuration.file.YamlConfiguration> yaml = legacy.readIfReadable();
        ConfigurationSection waiting = yaml.map(each -> each.getConfigurationSection("returns")).orElse(null);
        if (waiting == null) {
            return;
        }
        Map<UUID, List<String>> found = new LinkedHashMap<>();
        for (String owner : waiting.getKeys(false)) {
            try {
                found.put(UUID.fromString(owner), waiting.getStringList(owner));
            } catch (IllegalArgumentException notAnOwner) {
                log.warn("item-returns.yml lists items for '{}', which is not a player; they stay in the file.", owner);
            }
        }
        boolean ran = database.write(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO item_return (owner, item, added_at) VALUES (?, ?, ?)")) {
                for (Map.Entry<UUID, List<String>> entry : found.entrySet()) {
                    for (String item : entry.getValue()) {
                        insert.setString(1, entry.getKey().toString());
                        insert.setString(2, item);
                        insert.setLong(3, clock.getAsLong());
                        insert.executeUpdate();
                    }
                }
            }
        });
        if (!ran) {
            log.error("The items waiting in item-returns.yml could not be moved to the database; they stay in the file.");
            return;
        }
        if (!legacy.update(each -> found.keySet().forEach(owner -> each.set("returns." + owner, null)))) {
            log.error("The items of item-returns.yml are on the shared list, but the file could not be cleared: "
                    + "clear its 'returns' section by hand before the next start, or they would be listed twice.");
        }
        found.keySet().forEach(this::changed);
    }

    private void changed(UUID owner) {
        refresh(owner);
        bus.publish(TOPIC, owner.toString());
    }

    /** Reads one owner's list and shows it. Serialised, so a later read is never replaced by an earlier one. */
    private synchronized Optional<List<Waiting>> refresh(UUID owner) {
        Optional<List<Waiting>> read = database.read(connection -> waitingOf(connection, owner));
        read.ifPresent(list -> {
            if (list.isEmpty()) {
                shown.remove(owner);
            } else {
                shown.put(owner, list);
            }
        });
        return read;
    }

    private synchronized boolean refreshAll() {
        Optional<Map<UUID, List<Waiting>>> read = database.read(this::everything);
        read.ifPresent(all -> {
            shown.keySet().retainAll(all.keySet());
            shown.putAll(all);
        });
        return read.isPresent();
    }

    private static List<Waiting> waitingOf(Connection connection, UUID owner) throws SQLException {
        List<Waiting> list = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id, item FROM item_return WHERE owner = ? ORDER BY id")) {
            select.setString(1, owner.toString());
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    list.add(new Waiting(rows.getLong(1), rows.getString(2)));
                }
            }
        }
        return List.copyOf(list);
    }

    private Map<UUID, List<Waiting>> everything(Connection connection) throws SQLException {
        Map<UUID, List<Waiting>> all = new HashMap<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id, owner, item FROM item_return ORDER BY id");
             ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                try {
                    all.computeIfAbsent(UUID.fromString(rows.getString(2)), key -> new ArrayList<>())
                            .add(new Waiting(rows.getLong(1), rows.getString(3)));
                } catch (IllegalArgumentException notAnOwner) {
                    log.warn("A waiting item belongs to '{}', which is not a player.", rows.getString(2));
                }
            }
        }
        all.replaceAll((owner, list) -> List.copyOf(list));
        return all;
    }
}
