package de.raindancer.modules.homes;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.world.poi.PoiStore;
import de.raindancer.modules.homes.model.Home;
import de.raindancer.modules.homes.store.HomeCatalogue;
import de.raindancer.modules.homes.store.LegacyHomesFile;
import de.raindancer.modules.homes.store.SetHomePluginFile;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The migration end to end: a real file, read, brought into a real {@link PoiStore}, and left the way a
 * server that had to roll back would need it.
 *
 * <h2>Why this exists beside the two file-reader tests</h2>
 * {@code LegacyHomesFileTest} and {@code SetHomePluginFileTest} prove the parsing; this proves what
 * {@link HomeCatalogue} does with what they hand back — the merge-skip on a name already taken, and the
 * file being set aside so a second boot cannot re-run the import.
 */
class HomeCatalogueTest {

    private static final LogChannel LOG = Log.of("homes-test");

    @TempDir
    Path directory;

    private Database database;
    private HomeCatalogue homes;

    private Database openDatabase() {
        return Database.open(directory.resolve("core.db"), CoreSchema.CORE, () -> false);
    }

    @BeforeEach
    void setUp() {
        database = openDatabase();
        PoiStore store = new PoiStore(database);
        homes = new HomeCatalogue(store, store::flush);
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.close();
        }
    }

    private Path setHomeFileWith(String body) throws IOException {
        Path file = directory.resolve("sethome-homes.yml");
        Files.writeString(file, body);
        return file;
    }

    @Nested
    @DisplayName("importing SetHome's export")
    class ImportingSetHome {

        @Test
        @DisplayName("every home lands under its owner, with its exact place and facing")
        void everyHomeLands() throws IOException {
            Path file = setHomeFileWith("""
                    11111111-2222-3333-4444-555555555555:
                      base:
                        world: TTV
                        x: 121.5
                        y: 64.0
                        z: -310.5
                        yaw: 90.0
                        pitch: -12.0
                    """);
            UUID alice = UUID.fromString("11111111-2222-3333-4444-555555555555");

            int brought = homes.importSetHomePlugin(file, LOG);

            assertThat(brought).isEqualTo(1);
            Home base = homes.find(alice, "base").orElseThrow();
            assertThat(base.world()).isEqualTo("TTV");
            assertThat(base.poi().x()).isEqualTo(121.5);
            assertThat(base.poi().y()).isEqualTo(64.0);
            assertThat(base.poi().z()).isEqualTo(-310.5);
            assertThat(base.poi().yaw()).isEqualTo(90.0f);
            assertThat(base.poi().pitch()).isEqualTo(-12.0f);
        }

        @Test
        @DisplayName("a home the player already has under that name is left alone, not overwritten")
        void existingHomeIsNotOverwritten() throws IOException {
            UUID alice = UUID.fromString("11111111-2222-3333-4444-555555555555");
            World world = org.mockito.Mockito.mock(World.class);
            org.mockito.Mockito.when(world.getName()).thenReturn("TTV");
            homes.set(alice, "Alice", "base", new Location(world, 999, 999, 999));
            Path file = setHomeFileWith("""
                    11111111-2222-3333-4444-555555555555:
                      base:
                        world: TTV
                        x: 121.5
                        y: 64.0
                        z: -310.5
                        yaw: 0.0
                        pitch: 0.0
                    """);

            int brought = homes.importSetHomePlugin(file, LOG);

            assertThat(brought).isZero();
            assertThat(homes.find(alice, "base").orElseThrow().poi().x()).isEqualTo(999);
        }

        @Test
        @DisplayName("the file is set aside, so it cannot be imported a second time")
        void fileIsSetAsideAfterImport() throws IOException {
            Path file = setHomeFileWith("""
                    11111111-2222-3333-4444-555555555555:
                      base:
                        world: TTV
                        x: 1.0
                        y: 2.0
                        z: 3.0
                    """);

            homes.importSetHomePlugin(file, LOG);

            assertThat(Files.exists(file)).isFalse();
            assertThat(Files.exists(file.resolveSibling(file.getFileName() + ".imported"))).isTrue();
        }

        @Test
        @DisplayName("running the import again after it has run finds nothing left to bring across")
        void secondRunIsANoOp() throws IOException {
            Path file = setHomeFileWith("""
                    11111111-2222-3333-4444-555555555555:
                      base:
                        world: TTV
                        x: 1.0
                        y: 2.0
                        z: 3.0
                    """);

            homes.importSetHomePlugin(file, LOG);
            int secondRun = homes.importSetHomePlugin(file, LOG);

            assertThat(secondRun).isZero();
        }

        @Test
        @DisplayName("a missing file brings nothing across and is not an error")
        void missingFileIsFine() {
            int brought = homes.importSetHomePlugin(directory.resolve("nothing.yml"), LOG);

            assertThat(brought).isZero();
        }
    }

    @Nested
    @DisplayName("both migrations can run on the same server")
    class BothMigrationsTogether {

        @Test
        @DisplayName("RainsHomes' own legacy file and SetHome's are independent — one running does not consume the other")
        void independentSources() throws IOException {
            UUID alice = UUID.fromString("11111111-2222-3333-4444-555555555555");
            Path rainsHomesFile = directory.resolve("homes.yml");
            Files.writeString(rainsHomesFile, """
                    players:
                      11111111-2222-3333-4444-555555555555:
                        name: Alice
                        homes:
                          fromrains:
                            world: world
                            x: 1.0
                            y: 2.0
                            z: 3.0
                    """);
            Path setHomeFile = setHomeFileWith("""
                    11111111-2222-3333-4444-555555555555:
                      fromsethome:
                        world: TTV
                        x: 4.0
                        y: 5.0
                        z: 6.0
                    """);

            int fromLegacy = homes.importLegacy(rainsHomesFile, LOG);
            int fromSetHome = homes.importSetHomePlugin(setHomeFile, LOG);

            assertThat(fromLegacy).isEqualTo(1);
            assertThat(fromSetHome).isEqualTo(1);
            assertThat(homes.of(alice)).extracting(Home::name)
                    .containsExactlyInAnyOrder("fromrains", "fromsethome");
        }
    }

    @Nested
    @DisplayName("against the real server export")
    class LiveExport {

        private Path liveFile() throws IOException {
            Path file = directory.resolve("live-homes.yml");
            try (InputStream in = SetHomePluginFileTest.class
                    .getResourceAsStream("live-sethome-homes.yml")) {
                assertThat(in).as("the live fixture is missing").isNotNull();
                Files.copy(in, file);
            }
            return file;
        }

        @Test
        @DisplayName("all 144 homes across 21 players come across into the real store")
        void wholeServerImports() throws IOException {
            int brought = homes.importSetHomePlugin(liveFile(), LOG);

            assertThat(brought).isEqualTo(144);
        }

        @Test
        @DisplayName("every owner keeps exactly the number of homes they had in the export")
        void perOwnerCountsMatch() throws IOException {
            List<LegacyHomesFile.Entry> expected =
                    SetHomePluginFile.read(copyFreshLiveFile());
            homes.importSetHomePlugin(liveFile(), LOG);

            Set<UUID> owners = expected.stream().map(LegacyHomesFile.Entry::owner)
                    .collect(Collectors.toSet());
            for (UUID owner : owners) {
                long expectedCount = expected.stream().filter(e -> e.owner().equals(owner)).count();
                assertThat(homes.count(owner))
                        .as("owner %s", owner)
                        .isEqualTo((int) expectedCount);
            }
        }

        /** A second copy, read before the import moves the first one aside. */
        private Path copyFreshLiveFile() throws IOException {
            Path file = directory.resolve("live-homes-check.yml");
            try (InputStream in = SetHomePluginFileTest.class
                    .getResourceAsStream("live-sethome-homes.yml")) {
                Files.copy(in, file);
            }
            return file;
        }
    }
}
