package de.raindancer.modules.oneblock.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.testkit.TestDatabases;
import de.raindancer.modules.oneblock.model.BlockSpot;
import de.raindancer.modules.oneblock.model.EconomyMode;
import de.raindancer.modules.oneblock.model.Hold;
import de.raindancer.modules.oneblock.model.Island;
import de.raindancer.modules.oneblock.model.IslandDimension;
import de.raindancer.modules.oneblock.model.Spawn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("database")
class SqlIslandStorageTest {

    @TempDir
    Path folder;

    private Database database;
    private SqlIslandStorage storage;

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID FRIEND = UUID.randomUUID();

    @BeforeEach
    void open() {
        database = TestDatabases.open(folder.resolve("oneblock.db"), OneBlockDatabase.SCHEMA);
        storage = new SqlIslandStorage(database);
    }

    @AfterEach
    void close() {
        database.close();
    }

    private static Island full() {
        return Island.create(UUID.randomUUID(), OWNER, "Lilly", EconomyMode.SMP, 1234L)
                .withMember(FRIEND)
                .withShardsAdded(77)
                .unlocking(IslandDimension.NETHER).orElseThrow()
                .unlocking(IslandDimension.END, new BlockSpot(-800, 80, 800)).orElseThrow()
                .withBlock(IslandDimension.OVERWORLD, block -> block.withLevel("coal", 2).brokenOnceMore())
                .withSpawn(IslandDimension.NETHER, new Spawn(1.5, 65, -2.5, 90f, 10f))
                .withAutoUnload(false)
                .withHold(Optional.of(new Hold(Hold.Kind.DOWN, Hold.FOREVER, "Raindancer118")));
    }

    @Test
    @DisplayName("an island reads back exactly as it was written")
    void roundTrip() {
        Island island = full();
        assertThat(storage.save(island)).isTrue();

        assertThat(storage.loadAll()).hasValueSatisfying(all -> assertThat(all).containsExactly(island));
    }

    @Test
    @DisplayName("saving again replaces the island and its member index")
    void saveReplaces() {
        Island island = full();
        storage.save(island);
        storage.save(island.withoutMember(FRIEND).withShardsAdded(5));

        assertThat(storage.loadAll()).hasValueSatisfying(all -> {
            assertThat(all).hasSize(1);
            assertThat(all.getFirst().shards()).isEqualTo(82);
        });
        assertThat(storage.modeOf(FRIEND)).isEmpty();
        assertThat(storage.modeOf(OWNER)).contains("smp");
    }

    @Test
    @DisplayName("another server can read the money a player's island plays with")
    void modeForOtherServers() {
        storage.save(full());

        assertThat(storage.modeOf(FRIEND)).contains("smp");
        assertThat(storage.modeOf(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("an archived island is gone from play but its row stays")
    void archive() {
        Island island = full();
        storage.save(island);
        assertThat(storage.archive(island)).isTrue();

        assertThat(storage.loadAll()).hasValueSatisfying(all -> assertThat(all).isEmpty());
        assertThat(storage.modeOf(OWNER)).isEmpty();
        assertThat(database.read(connection -> {
            try (var query = connection.prepareStatement("SELECT COUNT(*) FROM island")) {
                var rows = query.executeQuery();
                rows.next();
                return rows.getInt(1);
            }
        })).contains(1);
    }

    @Test
    @DisplayName("where a player left is kept per player, the latest winning")
    void positions() {
        UUID player = UUID.randomUUID();
        storage.savePosition(player, new PlayerSpot("island_a", 1, 2, 3, 4f, 5f), 1);
        storage.savePosition(player, new PlayerSpot("island_b", -800.5, 81, 800.5, 0f, 0f), 2);

        assertThat(storage.position(player)).contains(new PlayerSpot("island_b", -800.5, 81, 800.5, 0f, 0f));
        assertThat(storage.position(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("a document that is not an island is skipped, the rest still load")
    void garbageIsSkipped() {
        storage.save(full());
        database.write(connection -> {
            try (var insert = connection.prepareStatement(
                    "INSERT INTO island (id, owner, mode, state, data, updated) VALUES ('x','y','pure','active','{oops',0)")) {
                insert.executeUpdate();
            }
        });

        assertThat(storage.loadAll()).hasValueSatisfying(all -> assertThat(all).hasSize(1));
    }

    @Test
    @DisplayName("everybody online's money in one query: islanders by their island, the rest left out")
    void manyModes() {
        storage.save(full());
        UUID nobody = UUID.randomUUID();
        UUID pure = UUID.randomUUID();
        storage.save(Island.create(UUID.randomUUID(), pure, "P", EconomyMode.PURE, 0));

        assertThat(storage.modesOf(java.util.List.of(OWNER, FRIEND, pure, nobody))).hasValueSatisfying(modes ->
                assertThat(modes).containsOnly(java.util.Map.entry(OWNER, "smp"), java.util.Map.entry(FRIEND, "smp"),
                        java.util.Map.entry(pure, "pure")));
        assertThat(storage.modesOf(java.util.List.of())).contains(java.util.Map.of());
    }
}
