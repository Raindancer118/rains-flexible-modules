package de.raindancer.modules.sends.store;

import de.raindancer.core.data.nbt.ItemBytes;
import de.raindancer.core.data.sql.Database;
import de.raindancer.modules.sends.model.Send;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The store against a real SQLite file, with items standing in for what the game would serialise. */
class SqliteSendStorageTest {

    @TempDir
    Path folder;

    private final Map<String, ItemStack> world = new HashMap<>();
    private final ItemBytes bytes = new ItemBytes() {
        @Override
        public int dataVersion() {
            return 1;
        }

        @Override
        public byte[] toBytes(ItemStack item) {
            return item.toString().getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public Optional<ItemStack> fromBytes(byte[] data) {
            return Optional.ofNullable(world.get(new String(data, StandardCharsets.UTF_8)));
        }
    };

    private Database database;
    private SqliteSendStorage storage;

    @BeforeEach
    void open() {
        database = Database.open(folder.resolve("sends.db"), SendsDatabase.SCHEMA, () -> false);
        storage = new SqliteSendStorage(database, bytes);
    }

    @AfterEach
    void close() {
        database.close();
    }

    private ItemStack item(String name, int amount) {
        ItemStack item = mock(ItemStack.class);
        when(item.toString()).thenReturn(name);
        when(item.getAmount()).thenReturn(amount);
        world.put(name, item);
        return item;
    }

    private Send send(ItemStack... items) {
        return Send.create(UUID.randomUUID(), "Alice", UUID.randomUUID(), "Bob", List.of(items), 1_000, 600_000);
    }

    @Test
    @DisplayName("a send comes back as it was written, items in order")
    void roundTrip() {
        Send written = send(item("diamond", 3), item("stone", 64));
        assertThat(storage.save(written)).isTrue();

        List<Send> loaded = storage.loadOpen();
        assertThat(loaded).hasSize(1);
        Send read = loaded.getFirst();
        assertThat(read.id()).isEqualTo(written.id());
        assertThat(read.senderName()).isEqualTo("Alice");
        assertThat(read.recipientName()).isEqualTo("Bob");
        assertThat(read.expiresAt()).isEqualTo(601_000);
        assertThat(read.state()).isEqualTo(Send.State.PENDING);
        assertThat(read.items()).extracting(Object::toString).containsExactly("diamond", "stone");
    }

    @Test
    @DisplayName("saving a change moves the state and leaves the items alone")
    void stateChange() {
        Send written = send(item("diamond", 3));
        storage.save(written);
        storage.save(written.withState(Send.State.RETURNING, 5_000));

        List<Send> loaded = storage.loadOpen();
        assertThat(loaded).hasSize(1);
        assertThat(loaded.getFirst().state()).isEqualTo(Send.State.RETURNING);
        assertThat(loaded.getFirst().settledAt()).isEqualTo(5_000);
        assertThat(loaded.getFirst().items()).hasSize(1);
    }

    @Test
    @DisplayName("a send that has been delivered or returned is not loaded again")
    void settledStaysSettled() {
        Send delivered = send(item("a", 1));
        Send returned = send(item("b", 1));
        Send waiting = send(item("c", 1));
        storage.save(delivered);
        storage.save(returned);
        storage.save(waiting);
        storage.save(delivered.withState(Send.State.DELIVERED, 2));
        storage.save(returned.withState(Send.State.RETURNED, 2));

        assertThat(storage.loadOpen()).extracting(Send::id).containsExactly(waiting.id());
    }

    @Test
    @DisplayName("a send whose items can no longer be read is not loaded, rather than loaded empty")
    void unreadableItemsAreNotLoadedAsNothing() {
        Send written = send(item("gone", 1));
        storage.save(written);
        world.clear();
        assertThat(storage.loadOpen()).isEmpty();
    }

    @Test
    @DisplayName("what was saved survives closing and opening the file")
    void survivesRestart() {
        Send written = send(item("diamond", 3));
        storage.save(written);
        database.close();
        open();
        assertThat(storage.loadOpen()).extracting(Send::id).containsExactly(written.id());
    }
}
