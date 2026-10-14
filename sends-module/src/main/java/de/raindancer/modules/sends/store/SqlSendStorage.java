package de.raindancer.modules.sends.store;

import de.raindancer.core.data.nbt.ItemBytes;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.sends.model.Send;
import org.bukkit.inventory.ItemStack;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;

/**
 * Sends in the shared database (SQLite on a server of its own, PostgreSQL on a network), the items as the game's own item bytes — which carry a data version, so a send written
 * before a server update still reads back after it.
 *
 * <p>Items are written once, with the send, and never changed: only the state moves afterwards, as a conditional
 * update of one row.
 */
public final class SqlSendStorage implements SendStorage {

    private static final LogChannel log = Log.of("sends");

    private final Database database;
    private final ItemBytes bytes;

    public SqlSendStorage(Database database, ItemBytes bytes) {
        this.database = database;
        this.bytes = bytes;
    }

    @Override
    public boolean save(Send send) {
        List<byte[]> encoded = new ArrayList<>(send.items().size());
        for (ItemStack item : send.items()) {
            encoded.add(bytes.toBytes(item));
        }
        return database.write(connection -> {
            try (PreparedStatement row = connection.prepareStatement(
                    "INSERT INTO send (id, sender, sender_name, recipient, recipient_name, created, expires, state, "
                            + "settled) VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING")) {
                row.setString(1, send.id().toString());
                row.setString(2, send.sender().toString());
                row.setString(3, send.senderName());
                row.setString(4, send.recipient().toString());
                row.setString(5, send.recipientName());
                row.setLong(6, send.createdAt());
                row.setLong(7, send.expiresAt());
                row.setString(8, send.state().name());
                row.setLong(9, send.settledAt());
                row.executeUpdate();
            }
            try (PreparedStatement item = connection.prepareStatement(
                    "INSERT INTO send_item (send, slot, item) VALUES (?,?,?) ON CONFLICT(send, slot) DO NOTHING")) {
                for (int slot = 0; slot < encoded.size(); slot++) {
                    item.setString(1, send.id().toString());
                    item.setInt(2, slot);
                    item.setBytes(3, encoded.get(slot));
                    item.addBatch();
                }
                item.executeBatch();
            }
        });
    }

    @Override
    public Written change(Send before, Send after) {
        AtomicInteger moved = new AtomicInteger();
        boolean ran = database.write(connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE send SET state = ?, settled = ? WHERE id = ? AND state = ? AND settled = ?")) {
                update.setString(1, after.state().name());
                update.setLong(2, after.settledAt());
                update.setString(3, before.id().toString());
                update.setString(4, before.state().name());
                update.setLong(5, before.settledAt());
                moved.set(update.executeUpdate());
            }
        });
        if (!ran) {
            return Written.FAILED;
        }
        return moved.get() == 1 ? Written.DONE : Written.LOST;
    }

    @Override
    public Optional<List<Send>> loadOpen() {
        return read(null);
    }

    @Override
    public Optional<Optional<Send>> load(UUID id) {
        return read(id).map(sends -> sends.stream().findFirst());
    }

    /** The open sends, or only the one of this id. */
    private Optional<List<Send>> read(UUID only) {
        String where = only == null ? "" : " AND s.id = ?";
        return database.read(connection -> {
            Map<String, List<ItemStack>> items = new LinkedHashMap<>();
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT i.send, i.item FROM send_item i JOIN send s ON s.id = i.send "
                            + "WHERE s.state IN ('PENDING','RETURNING')" + where + " ORDER BY i.send, i.slot")) {
                if (only != null) {
                    query.setString(1, only.toString());
                }
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        Optional<ItemStack> item = bytes.fromBytes(rows.getBytes(2));
                        if (item.isEmpty()) {
                            // Kept loud: an item that cannot be read back is somebody's item, and this is the only
                            // place that knows which send it belonged to.
                            log.error("An item of send {} could not be read back and is missing from it.",
                                    rows.getString(1));
                            continue;
                        }
                        items.computeIfAbsent(rows.getString(1), key -> new ArrayList<>()).add(item.get());
                    }
                }
            }
            List<Send> sends = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT s.id, s.sender, s.sender_name, s.recipient, s.recipient_name, s.created, s.expires, "
                            + "s.state, s.settled FROM send s WHERE s.state IN ('PENDING','RETURNING')" + where
                            + " ORDER BY s.created")) {
                if (only != null) {
                    query.setString(1, only.toString());
                }
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        List<ItemStack> held = items.getOrDefault(rows.getString(1), List.of());
                        if (held.isEmpty()) {
                            log.error("Send {} has no readable items left and is not loaded.", rows.getString(1));
                            continue;
                        }
                        sends.add(new Send(UUID.fromString(rows.getString(1)), UUID.fromString(rows.getString(2)),
                                rows.getString(3), UUID.fromString(rows.getString(4)), rows.getString(5), held,
                                rows.getLong(6), rows.getLong(7), Send.State.valueOf(rows.getString(8)),
                                rows.getLong(9)));
                    }
                }
            }
            return sends;
        });
    }
}
