package de.raindancer.modules.guilds.store;

import de.raindancer.core.data.nbt.ItemText;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.stash.ArmourPiece;
import de.raindancer.core.data.stash.Stash;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Guild vaults, for the network: every stack is a row, and a stack leaves the vault by deleting its row — checked
 * against what the row still holds — so it leaves once, on one server, however many click at the same time.
 * Putting items in tops up a stack by changing its row from what it was, or adds rows while there is room.
 *
 * <p>A vault is whatever {@link GuildBook#vaultOwner} names. Lines this server cannot read (an item from a mod or
 * a newer version) stay as rows and are never dropped; they show up as {@code kept}, and a vault holding any is
 * not empty.
 */
public final class VaultStore {

    private static final int ATTEMPTS = 4;
    private static final String ITEM = "";
    private static final String ARMOUR = "armour:";

    /** What a vault holds, as the database has it. */
    public record Contents(List<ItemStack> items, Map<ArmourPiece, ItemStack> armour, List<String> kept) {

        public boolean isEmpty() {
            return items.isEmpty() && armour.isEmpty() && kept.isEmpty();
        }
    }

    private record Row(String id, String slot, long pos, String line) {
    }

    private final Database database;
    private final ItemText codec;

    public VaultStore(Database database, ItemText codec) {
        this.database = database;
        this.codec = codec;
    }

    // ------------------------------------------------------------------ reading

    /** The vault as it is now; empty when the database could not be asked — which is not an empty vault. */
    public Optional<Contents> read(String vault) {
        return database.read(connection -> contents(rows(connection, vault)));
    }

    private Contents contents(List<Row> rows) {
        List<ItemStack> items = new ArrayList<>();
        Map<ArmourPiece, ItemStack> armour = new EnumMap<>(ArmourPiece.class);
        List<String> kept = new ArrayList<>();
        for (Row row : rows) {
            ItemStack item = codec.read(row.line());
            if (item == null) {
                kept.add(row.line());
            } else if (row.slot().equals(ITEM)) {
                items.add(item);
            } else {
                piece(row.slot()).ifPresent(piece -> armour.put(piece, item));
            }
        }
        return new Contents(items, armour, kept);
    }

    /** The vault as a {@link Stash} of {@code capacity} — a view to draw from, never to change. */
    public Optional<Stash> view(String vault, int capacity) {
        return read(vault).map(held -> Stash.restored(capacity, held.items(), held.armour(), held.kept()));
    }

    private static Optional<ArmourPiece> piece(String slot) {
        try {
            return slot.startsWith(ARMOUR) ? Optional.of(ArmourPiece.valueOf(slot.substring(ARMOUR.length()))) : Optional.empty();
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }

    private static List<Row> rows(Connection connection, String vault) throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT id, slot, pos, item FROM guild_vault WHERE vault = ? ORDER BY pos, id")) {
            query.setString(1, vault);
            try (ResultSet found = query.executeQuery()) {
                while (found.next()) {
                    rows.add(new Row(found.getString(1), found.getString(2), found.getLong(3), found.getString(4)));
                }
            }
        }
        return rows;
    }

    // ------------------------------------------------------------------ putting in

    /**
     * Puts a copy of {@code item} in: a piece of armour on its empty stand, everything else topping up similar
     * stacks first and then starting new ones while there are fewer than {@code capacity}.
     *
     * @param guild the guild the vault belongs to; nothing goes into a vault whose guild is gone
     * @return how many were taken; the caller removes exactly that many from wherever it came from
     */
    public int deposit(String vault, String guild, ItemStack item, int capacity) {
        if (item == null || item.isEmpty()) {
            return 0;
        }
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            int[] accepted = new int[1];
            boolean ran = database.write(connection -> {
                if (!lock(connection, guild)) {
                    return;
                }
                List<Row> rows = rows(connection, vault);
                int left = item.getAmount();
                int max = Math.max(1, item.getMaxStackSize());
                long next = rows.stream().mapToLong(Row::pos).max().orElse(0) + 1;
                Optional<ArmourPiece> piece = ArmourPiece.of(item.getType());
                if (piece.isPresent() && rows.stream().noneMatch(row -> row.slot().equals(ARMOUR + piece.get().name()))) {
                    int placed = Math.min(left, max);
                    insert(connection, vault, ARMOUR + piece.get().name(), next++, item.asQuantity(placed));
                    left -= placed;
                }
                int stacks = 0;
                for (Row row : rows) {
                    if (!row.slot().equals(ITEM)) {
                        continue;
                    }
                    ItemStack stack = codec.read(row.line());
                    if (stack == null) {
                        continue;
                    }
                    stacks++;
                    if (left > 0 && stack.getAmount() < max && stack.isSimilar(item)) {
                        int added = Math.min(left, max - stack.getAmount());
                        stack.setAmount(stack.getAmount() + added);
                        String now = codec.write(stack);
                        if (now == null) {
                            continue;
                        }
                        change(connection, row, now);
                        left -= added;
                    }
                }
                while (left > 0 && stacks < capacity) {
                    int placed = Math.min(left, max);
                    insert(connection, vault, ITEM, next++, item.asQuantity(placed));
                    stacks++;
                    left -= placed;
                }
                accepted[0] = item.getAmount() - left;
            });
            if (ran) {
                return accepted[0];
            }
        }
        return 0;
    }

    /** Adds a stack at the end, over any capacity: what did not fit in an inventory goes back in. */
    public boolean putBack(String vault, String guild, ItemStack item) {
        if (item == null || item.isEmpty()) {
            return true;
        }
        boolean[] put = new boolean[1];
        boolean ran = database.write(connection -> {
            if (!lock(connection, guild)) {
                return;
            }
            long next = rows(connection, vault).stream().mapToLong(Row::pos).max().orElse(0) + 1;
            insert(connection, vault, ITEM, next, item);
            put[0] = true;
        });
        return ran && put[0];
    }

    /** Takes the guild's row so a disband and a deposit cannot interleave; false when the guild is gone. */
    private static boolean lock(Connection connection, String guild) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("UPDATE guild SET xp = xp WHERE id = ?")) {
            update.setString(1, guild);
            return update.executeUpdate() == 1;
        }
    }

    private void insert(Connection connection, String vault, String slot, long pos, ItemStack item) throws SQLException {
        String line = codec.write(item);
        if (line == null) {
            throw new SQLException("an item that cannot be written was put into a vault");
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO guild_vault (id, vault, slot, pos, item) VALUES (?, ?, ?, ?, ?)")) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, vault);
            insert.setString(3, slot);
            insert.setLong(4, pos);
            insert.setString(5, line);
            insert.executeUpdate();
        }
    }

    /** Changes a stack to {@code line}, only if the row still holds what it held when it was read. */
    private static void change(Connection connection, Row row, String line) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("UPDATE guild_vault SET item = ? WHERE id = ? AND item = ?")) {
            update.setString(1, line);
            update.setString(2, row.id());
            update.setString(3, row.line());
            if (update.executeUpdate() != 1) {
                throw new SQLException("a vault stack changed while it was being topped up");
            }
        }
    }

    // ------------------------------------------------------------------ taking out

    /**
     * Takes one entry out, if it is still exactly what the screen showed.
     *
     * @param index where in the list of items it was drawn
     * @return the stack, or null when the entry has gone or changed since it was drawn
     */
    public ItemStack take(String vault, int index, ItemStack shown) {
        if (index < 0 || shown == null) {
            return null;
        }
        ItemStack[] taken = new ItemStack[1];
        database.write(connection -> {
            List<Row> items = new ArrayList<>();
            List<ItemStack> stacks = new ArrayList<>();
            for (Row row : rows(connection, vault)) {
                ItemStack stack = row.slot().equals(ITEM) ? codec.read(row.line()) : null;
                if (stack != null) {
                    items.add(row);
                    stacks.add(stack);
                }
            }
            if (index >= items.size()) {
                return;
            }
            ItemStack there = stacks.get(index);
            if (!there.isSimilar(shown) || there.getAmount() != shown.getAmount()) {
                return;
            }
            if (delete(connection, items.get(index))) {
                taken[0] = there;
            }
        });
        return taken[0];
    }

    /** Takes whatever is on this stand off it, or null when it is empty. */
    public ItemStack takeArmour(String vault, ArmourPiece piece) {
        ItemStack[] taken = new ItemStack[1];
        database.write(connection -> {
            for (Row row : rows(connection, vault)) {
                if (row.slot().equals(ARMOUR + piece.name())) {
                    ItemStack there = codec.read(row.line());
                    if (there != null && delete(connection, row)) {
                        taken[0] = there;
                    }
                    return;
                }
            }
        });
        return taken[0];
    }

    private static boolean delete(Connection connection, Row row) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM guild_vault WHERE id = ? AND item = ?")) {
            delete.setString(1, row.id());
            delete.setString(2, row.line());
            return delete.executeUpdate() == 1;
        }
    }
}
