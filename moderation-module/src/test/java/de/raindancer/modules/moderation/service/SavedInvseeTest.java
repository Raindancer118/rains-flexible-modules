package de.raindancer.modules.moderation.service;

import de.raindancer.core.data.inventory.SavedInventories;
import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.moderation.invsee.Carried;
import de.raindancer.core.moderation.invsee.Section;
import de.raindancer.core.testkit.TestDatabases;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** What an inventory looked at from another server is made of: the newest saved copy, never written back. */
@Tag("database")
class SavedInvseeTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @TempDir
    Path folder;

    private Database database;
    private SavedInventories store;
    /** The "bytes" of a test copy are a key into the items it decodes to. */
    private final Map<Byte, ItemStack[]> items = new java.util.HashMap<>();
    private final Function<byte[], ItemStack[]> decoder = bytes -> {
        ItemStack[] found = items.get(bytes[0]);
        if (found == null) {
            throw new IllegalArgumentException("not items");
        }
        return found;
    };

    @BeforeEach
    void open() {
        database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE);
        store = new SavedInventories(database, System::currentTimeMillis);
    }

    @AfterEach
    void close() {
        database.close();
    }

    private SavedInvsee invsee(boolean network) {
        return new SavedInvsee(null, null, null, null, () -> store, decoder, () -> network);
    }

    private void save(String server, long at, byte contents, byte ender) {
        assertThat(store.save(new SavedInventories.Saved(server, ALEX, at, new byte[]{contents}, new byte[]{ender},
                1, 0f, 20, 20, 5f, ""))).isTrue();
    }

    @Test
    @DisplayName("this server's copy is the one shown — the SMP inventory on the SMP, though another server saved later")
    void thisServersCopy() {
        ItemStack[] onSmp = new ItemStack[41];
        ItemStack[] onOneblock = new ItemStack[41];
        onSmp[0] = mock(ItemStack.class);
        onOneblock[0] = mock(ItemStack.class);
        items.put((byte) 1, onSmp);
        items.put((byte) 2, onOneblock);
        items.put((byte) 3, new ItemStack[27]);
        save("smp", 100, (byte) 1, (byte) 3);
        save("oneblock", 200, (byte) 2, (byte) 3);

        SavedInvsee.Copy copy = invsee(true).here(ALEX, "smp").orElseThrow();

        assertThat(copy.server()).isEqualTo("smp");
        assertThat(copy.savedAt()).isEqualTo(100);
        assertThat(copy.carried().at(Section.HOTBAR, 0)).isSameAs(onSmp[0]);
        assertThat(invsee(true).here(ALEX, "arena")).as("never played there").isEmpty();
    }

    @Test
    @DisplayName("slots land where the game keeps them: hotbar and backpack, armour from the boots up, off hand, ender chest")
    void layout() {
        ItemStack[] contents = new ItemStack[41];
        ItemStack hotbar = mock(ItemStack.class);
        ItemStack backpack = mock(ItemStack.class);
        ItemStack boots = mock(ItemStack.class);
        ItemStack helmet = mock(ItemStack.class);
        ItemStack shield = mock(ItemStack.class);
        contents[8] = hotbar;
        contents[9] = backpack;
        contents[36] = boots;
        contents[39] = helmet;
        contents[40] = shield;
        ItemStack[] ender = new ItemStack[27];
        ItemStack pearl = mock(ItemStack.class);
        ender[26] = pearl;

        Carried<ItemStack> carried = SavedInvsee.carriedOf(contents, ender);

        assertThat(carried.at(Section.HOTBAR, 8)).isSameAs(hotbar);
        assertThat(carried.at(Section.STORAGE, 0)).isSameAs(backpack);
        assertThat(carried.at(Section.ARMOUR, 0)).isSameAs(helmet);
        assertThat(carried.at(Section.ARMOUR, 3)).isSameAs(boots);
        assertThat(carried.at(Section.OFF_HAND, 0)).isSameAs(shield);
        assertThat(carried.at(Section.ENDER_CHEST, 26)).isSameAs(pearl);
        assertThat(carried.count()).isEqualTo(6);
        assertThat(SavedInvsee.carriedOf(null, null).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("nothing saved is nothing to show, and a copy that cannot be decoded is too")
    void nothingToShow() {
        assertThat(invsee(true).here(ALEX, "smp")).isEmpty();

        save("smp", 100, (byte) 9, (byte) 9);
        assertThat(invsee(true).here(ALEX, "smp")).isEmpty();
    }

    @Test
    @DisplayName("a core that keeps no inventories shows nothing rather than failing")
    void noStore() {
        SavedInvsee none = new SavedInvsee(null, null, null, null, () -> {
            throw new UnsupportedOperationException("this core keeps no inventories");
        }, decoder, () -> true);

        assertThat(none.here(ALEX, "smp")).isEmpty();
    }

    @Test
    @DisplayName("saved copies are used only on a network; the window's source refuses every write")
    void readOnly() {
        assertThat(invsee(false).wanted()).isFalse();
        assertThat(invsee(true).wanted()).isTrue();

        Carried<ItemStack> carried = Carried.empty();
        SavedInvsee.ReadOnly source = new SavedInvsee.ReadOnly(carried);
        assertThat(source.read(ALEX)).contains(carried);
        assertThat(source.set(ALEX, Section.HOTBAR, 0, mock(ItemStack.class))).isFalse();
        assertThat(source.write(ALEX, carried)).isFalse();
    }

    @Test
    @DisplayName("looking never changes any server's copy")
    void neverWrites() {
        ItemStack[] one = new ItemStack[41];
        one[0] = mock(ItemStack.class);
        items.put((byte) 1, one);
        items.put((byte) 3, new ItemStack[27]);
        save("arena", 200, (byte) 1, (byte) 3);

        invsee(true).here(ALEX, "smp");

        SavedInventories.Saved after = store.on("arena", ALEX).orElseThrow();
        assertThat(after.savedAt()).isEqualTo(200);
        assertThat(after.contents()).containsExactly(1);
        assertThat(store.everywhere(ALEX)).hasSize(1);
    }
}
