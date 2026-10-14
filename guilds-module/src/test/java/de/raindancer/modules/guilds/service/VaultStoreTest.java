package de.raindancer.modules.guilds.service;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.sql.Dialect;
import de.raindancer.core.data.stash.ArmourPiece;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.testkit.TestDatabases;
import de.raindancer.modules.guilds.model.Fixtures;
import de.raindancer.modules.guilds.model.Guild;
import de.raindancer.modules.guilds.store.GuildBook;
import de.raindancer.modules.guilds.store.GuildsDatabase;
import de.raindancer.modules.guilds.store.VaultImport;
import de.raindancer.modules.guilds.store.VaultStore;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** A guild's vault as rows in the network's database, used from two servers. */
@Tag("database")
class VaultStoreTest {

    @TempDir
    Path folder;

    private Database first;
    private Database second;
    private VaultStore smp;
    private VaultStore arena;
    private GuildBook book;
    private Guild elves;
    private String vault;

    @BeforeEach
    void start() {
        first = TestDatabases.open(folder.resolve("guilds.db"), GuildsDatabase.SCHEMA);
        // One writer connection on a SQLite file: there the servers share it, as on PostgreSQL they would not.
        second = TestDatabases.dialect() == Dialect.SQLITE ? first
                : TestDatabases.open(folder.resolve("guilds.db"), GuildsDatabase.SCHEMA);
        smp = TestVaults.in(first);
        arena = TestVaults.in(second);
        book = new GuildBook(first, problem -> { });
        book.load();
        elves = Fixtures.guild("Elves");
        book.create(elves);
        vault = GuildBook.vaultOwner(elves).toString();
    }

    @AfterEach
    void stop() {
        first.close();
        if (second != first) {
            second.close();
        }
    }

    private static ItemStack dirt(int amount) {
        return TestVaults.stack(Material.DIRT, amount);
    }

    private static <T> List<T> together(Callable<T> a, Callable<T> b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> running = new ArrayList<>();
        for (Callable<T> each : List.of(a, b)) {
            running.add(pool.submit(() -> {
                go.await();
                return each.call();
            }));
        }
        go.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> each : running) {
            results.add(each.get());
        }
        pool.shutdown();
        return results;
    }

    @Test
    @DisplayName("what one server puts in, the other sees; stacks of one kind are topped up, up to the room there is")
    void depositing() {
        assertThat(smp.deposit(vault, "elves", dirt(40), 2)).isEqualTo(40);
        assertThat(arena.deposit(vault, "elves", dirt(40), 2)).isEqualTo(40);
        assertThat(smp.read(vault).orElseThrow().items()).extracting(ItemStack::getAmount).containsExactly(64, 16);
        assertThat(arena.deposit(vault, "elves", dirt(100), 2)).as("only what fits in two stacks").isEqualTo(48);
        assertThat(arena.deposit(vault, "elves", dirt(1), 2)).isZero();
    }

    @Test
    @DisplayName("a stack leaves the vault once, whichever server and however many click at the same moment")
    void takenOnce() throws Exception {
        smp.deposit(vault, "elves", dirt(5), 3);
        ItemStack shown = smp.read(vault).orElseThrow().items().getFirst();
        List<ItemStack> taken = together(() -> smp.take(vault, 0, shown), () -> arena.take(vault, 0, shown));
        assertThat(taken).filteredOn(java.util.Objects::nonNull).hasSize(1);
        assertThat(smp.read(vault).orElseThrow().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("what a screen drew is taken only while it is still that: an entry that changed or moved is refused")
    void takingWhatWasShown() {
        smp.deposit(vault, "elves", dirt(5), 3);
        ItemStack shown = smp.read(vault).orElseThrow().items().getFirst();
        arena.deposit(vault, "elves", dirt(3), 3);
        assertThat(smp.take(vault, 0, shown)).as("it is eight now").isNull();
        assertThat(smp.take(vault, 4, shown)).isNull();
        assertThat(smp.take(vault, 0, smp.read(vault).orElseThrow().items().getFirst())).isNotNull();
    }

    @Test
    @DisplayName("armour goes on its stand, comes off once, and a second piece of the kind waits as a stack")
    void armour() {
        ItemStack helmet = TestVaults.stack(Material.DIAMOND_HELMET, 1);
        assertThat(smp.deposit(vault, "elves", helmet, 3)).isEqualTo(1);
        assertThat(smp.read(vault).orElseThrow().armour()).containsKey(ArmourPiece.of(Material.DIAMOND_HELMET).orElseThrow());
        assertThat(smp.read(vault).orElseThrow().items()).isEmpty();
        List<ItemStack> taken = new ArrayList<>();
        taken.add(smp.takeArmour(vault, ArmourPiece.of(Material.DIAMOND_HELMET).orElseThrow()));
        taken.add(arena.takeArmour(vault, ArmourPiece.of(Material.DIAMOND_HELMET).orElseThrow()));
        assertThat(taken).filteredOn(java.util.Objects::nonNull).hasSize(1);
    }

    @Test
    @DisplayName("nothing goes into a vault whose guild is gone, and a guild with something in its vault is not disbanded")
    void guildAndVault() {
        smp.deposit(vault, "elves", dirt(2), 3);
        assertThat(book.update(elves.id(), g -> g.withBank(0))).isNotNull();
        assertThat(book.disband(elves.id())).as("the vault is not empty").isEmpty();
        smp.take(vault, 0, smp.read(vault).orElseThrow().items().getFirst());
        assertThat(book.disband(elves.id())).isPresent();
        assertThat(arena.deposit(vault, "elves", dirt(2), 3)).isZero();
        assertThat(arena.putBack(vault, "elves", dirt(2))).isFalse();
        assertThat(arena.read(vault).orElseThrow().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("an unreadable vault line is kept as it is, counts as something in the vault, and is never dropped")
    void unreadableLines() throws Exception {
        Path folderOfDocs = Files.createDirectories(folder.resolve("vaults"));
        Files.writeString(folderOfDocs.resolve(GuildBook.vaultOwner(elves) + ".yml"),
                "items:\n  - not-an-item\n  - " + java.util.Base64.getEncoder().encodeToString("DIRT:7".getBytes()) + "\nkept:\n  - from-a-mod\n");
        assertThat(VaultImport.run(first, List.of(elves), owner -> new YamlStore(folderOfDocs.resolve(owner + ".yml")), "smp",
                problem -> { })).isEqualTo(1);
        assertThat(VaultImport.run(first, List.of(elves), owner -> new YamlStore(folderOfDocs.resolve(owner + ".yml")), "smp",
                problem -> { })).as("once").isZero();
        VaultStore.Contents held = smp.read(vault).orElseThrow();
        assertThat(held.items()).extracting(ItemStack::getAmount).containsExactly(7);
        assertThat(held.kept()).containsExactlyInAnyOrder("not-an-item", "from-a-mod");
        assertThat(held.isEmpty()).isFalse();
        smp.take(vault, 0, held.items().getFirst());
        VaultImport.run(first, List.of(elves), owner -> new YamlStore(folderOfDocs.resolve(owner + ".yml")), "smp", problem -> { });
        assertThat(smp.read(vault).orElseThrow().items()).as("what was taken out stays out").isEmpty();
    }

    @Test
    @DisplayName("a vault that cannot be read is not an empty one")
    void unreadable() throws Exception {
        Database broken = TestDatabases.unusable(folder.resolve("broken.db"), GuildsDatabase.SCHEMA);
        assertThat(TestVaults.in(broken).read(vault)).isEmpty();
        assertThat(TestVaults.in(broken).deposit(vault, "elves", dirt(1), 3)).isZero();
        broken.close();
    }
}
