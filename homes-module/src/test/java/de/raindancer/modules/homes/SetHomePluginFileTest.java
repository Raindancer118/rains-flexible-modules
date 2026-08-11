package de.raindancer.modules.homes;

import de.raindancer.modules.homes.store.LegacyHomesFile;
import de.raindancer.modules.homes.store.SetHomePluginFile;
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
 * Reading the homes a server that used to run the third-party {@code SetHome} plugin already has.
 *
 * <h2>Why a separate fixture from {@code LegacyHomesFileTest}</h2>
 * SetHome's {@code homes.yml} has never had a {@code players:} wrapper, an owner name or a created
 * timestamp — it is the player id straight at the top of the file, holding home names straight at
 * coordinates. A fixture that agreed with {@link LegacyHomesFile} would prove nothing about this reader.
 *
 * <p>The {@link #liveExport()} case is read from an actual export of a server's {@code plugins/SetHome/}
 * folder, not hand-written — the fixture most likely to catch a field this reader gets wrong is one this
 * module's author did not write to make the test pass.
 */
class SetHomePluginFileTest {

    private static final UUID ALICE = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID BOB = UUID.fromString("66666666-7777-8888-9999-000000000000");

    @TempDir
    Path directory;

    private Path fileWith(String body) throws IOException {
        Path file = directory.resolve("homes.yml");
        Files.writeString(file, body);
        return file;
    }

    /** Exactly the shape SetHome wrote: no {@code players:}, no owner name, no icon, no created date. */
    private Path twoPlayers() throws IOException {
        return fileWith("""
                11111111-2222-3333-4444-555555555555:
                  home:
                    world: TTV
                    x: 121.5
                    y: 64.0
                    z: -310.5
                    yaw: 90.0
                    pitch: -12.0
                  mine:
                    world: TTV_nether
                    x: 10.0
                    y: 32.0
                    z: 20.0
                    yaw: 0.0
                    pitch: 0.0
                66666666-7777-8888-9999-000000000000:
                  base:
                    world: TTV
                    x: 1.0
                    y: 2.0
                    z: 3.0
                    yaw: 0.0
                    pitch: 0.0
                """);
    }

    @Nested
    @DisplayName("reading what is there")
    class Reading {

        @Test
        @DisplayName("every home of every player is found")
        void everythingIsRead() throws IOException {
            List<LegacyHomesFile.Entry> found = SetHomePluginFile.read(twoPlayers());

            assertThat(found).hasSize(3);
            assertThat(found).extracting(LegacyHomesFile.Entry::name)
                    .containsExactlyInAnyOrder("home", "mine", "base");
        }

        @Test
        @DisplayName("each home keeps its owner")
        void ownersAreKept() throws IOException {
            List<LegacyHomesFile.Entry> found = SetHomePluginFile.read(twoPlayers());

            assertThat(found).filteredOn(entry -> entry.name().equals("base"))
                    .singleElement()
                    .extracting(LegacyHomesFile.Entry::owner)
                    .isEqualTo(BOB);
            assertThat(found).filteredOn(entry -> entry.owner().equals(ALICE)).hasSize(2);
        }

        @Test
        @DisplayName("every coordinate, the world and the facing survive exactly")
        void thePlaceSurvives() throws IOException {
            LegacyHomesFile.Entry home = SetHomePluginFile.read(twoPlayers()).stream()
                    .filter(entry -> entry.name().equals("home"))
                    .findFirst().orElseThrow();

            assertThat(home.world()).isEqualTo("TTV");
            assertThat(home.x()).isEqualTo(121.5);
            assertThat(home.y()).isEqualTo(64.0);
            assertThat(home.z()).isEqualTo(-310.5);
            assertThat(home.yaw())
                    .as("stored as a double on disk and read back as a float — a home that arrives "
                            + "facing the wrong way is one people notice immediately")
                    .isEqualTo(90.0f);
            assertThat(home.pitch()).isEqualTo(-12.0f);
        }

        @Test
        @DisplayName("there is no owner name, icon or created date to bring across — SetHome never had them")
        void theAbsentFieldsComeBackBlank() throws IOException {
            LegacyHomesFile.Entry home = SetHomePluginFile.read(twoPlayers()).stream()
                    .filter(entry -> entry.name().equals("home"))
                    .findFirst().orElseThrow();

            assertThat(home.ownerName()).isEmpty();
            assertThat(home.icon()).isEmpty();
            assertThat(home.createdAt()).isZero();
        }
    }

    @Nested
    @DisplayName("a file that is not quite right")
    class Broken {

        @Test
        @DisplayName("no file at all is nothing to import, not a failure")
        void noFile() {
            assertThat(SetHomePluginFile.read(directory.resolve("nothing.yml"))).isEmpty();
            assertThat(SetHomePluginFile.read(null)).isEmpty();
        }

        @Test
        @DisplayName("an empty file is nothing to import")
        void emptyFile() throws IOException {
            assertThat(SetHomePluginFile.read(fileWith(""))).isEmpty();
        }

        @Test
        @DisplayName("a top-level key that is not a uuid is stepped over, and the rest still loads")
        void aBadIdIsSkipped() throws IOException {
            Path file = fileWith("""
                    not-a-uuid:
                      home:
                        world: TTV
                        x: 1.0
                        y: 2.0
                        z: 3.0
                    66666666-7777-8888-9999-000000000000:
                      base:
                        world: TTV
                        x: 1.0
                        y: 2.0
                        z: 3.0
                    """);

            assertThat(SetHomePluginFile.read(file))
                    .singleElement()
                    .extracting(LegacyHomesFile.Entry::owner).isEqualTo(BOB);
        }

        @Test
        @DisplayName("a home with no world is stepped over rather than imported as nowhere")
        void aHomeWithNoWorldIsSkipped() throws IOException {
            Path file = fileWith("""
                    66666666-7777-8888-9999-000000000000:
                      nowhere:
                        x: 1.0
                        y: 2.0
                        z: 3.0
                      base:
                        world: TTV
                        x: 1.0
                        y: 2.0
                        z: 3.0
                    """);

            assertThat(SetHomePluginFile.read(file))
                    .singleElement()
                    .extracting(LegacyHomesFile.Entry::name).isEqualTo("base");
        }

        @Test
        @DisplayName("a name the rule would refuse is stepped over rather than stored unreadably")
        void anImpossibleNameIsSkipped() throws IOException {
            Path file = fileWith("""
                    66666666-7777-8888-9999-000000000000:
                      'my base':
                        world: TTV
                        x: 1.0
                        y: 2.0
                        z: 3.0
                      base:
                        world: TTV
                        x: 1.0
                        y: 2.0
                        z: 3.0
                    """);

            assertThat(SetHomePluginFile.read(file))
                    .singleElement()
                    .extracting(LegacyHomesFile.Entry::name).isEqualTo("base");
        }

        @Test
        @DisplayName("a file that is not YAML at all is nothing to import, not a crash")
        void rubbishIsNotFatal() throws IOException {
            assertThat(SetHomePluginFile.read(fileWith("this: is: not: yaml:\n\t "))).isEmpty();
        }

        @Test
        @DisplayName("a player id with no homes contributes nothing")
        void noHomesIsNothing() throws IOException {
            assertThat(SetHomePluginFile.read(fileWith("""
                    66666666-7777-8888-9999-000000000000: {}
                    """))).isEmpty();
        }
    }

    @Nested
    @DisplayName("a name that is capitalised on disk")
    class Normalising {

        @Test
        @DisplayName("it is folded, because the name is the key a command types")
        void namesAreFolded() throws IOException {
            Path file = fileWith("""
                    66666666-7777-8888-9999-000000000000:
                      Base:
                        world: TTV
                        x: 1.0
                        y: 2.0
                        z: 3.0
                    """);

            assertThat(SetHomePluginFile.read(file))
                    .singleElement()
                    .extracting(LegacyHomesFile.Entry::name)
                    .as("SetHome's own commands were case-insensitive, so a hand-capitalised key has "
                            + "always been reachable as lower case")
                    .isEqualTo("base");
        }
    }

    @Nested
    @DisplayName("finding the file when it is not exactly where it is expected")
    class Locating {

        @Test
        @DisplayName("the expected place wins when the file is actually there")
        void theExpectedPlace() throws IOException {
            Path plugins = Files.createDirectory(directory.resolve("plugins"));
            Path setHome = Files.createDirectory(plugins.resolve("SetHome"));
            Files.writeString(setHome.resolve("homes.yml"), "");
            Path moduleData = Files.createDirectory(plugins.resolve("RainsHomes"));

            assertThat(SetHomePluginFile.locate(plugins, moduleData))
                    .contains(setHome.resolve("homes.yml"));
        }

        @Test
        @DisplayName("a folder cased differently is still found")
        void differentCasing() throws IOException {
            Path plugins = Files.createDirectory(directory.resolve("plugins"));
            Path setHome = Files.createDirectory(plugins.resolve("sethome"));
            Files.writeString(setHome.resolve("homes.yml"), "");
            Path moduleData = Files.createDirectory(plugins.resolve("RainsHomes"));

            assertThat(SetHomePluginFile.locate(plugins, moduleData))
                    .contains(setHome.resolve("homes.yml"));
        }

        @Test
        @DisplayName("an export copied next to this module's own data is found")
        void copiedIntoModuleData() throws IOException {
            Path plugins = Files.createDirectory(directory.resolve("plugins"));
            Path moduleData = Files.createDirectory(plugins.resolve("RainsHomes"));
            Path copiedIn = Files.createDirectory(moduleData.resolve("SetHome"));
            Files.writeString(copiedIn.resolve("homes.yml"), "");

            assertThat(SetHomePluginFile.locate(plugins, moduleData))
                    .contains(copiedIn.resolve("homes.yml"));
        }

        @Test
        @DisplayName("the export renamed and dropped straight into this module's data is found")
        void droppedInByHand() throws IOException {
            Path plugins = Files.createDirectory(directory.resolve("plugins"));
            Path moduleData = Files.createDirectory(plugins.resolve("RainsHomes"));
            Files.writeString(moduleData.resolve("sethome-homes.yml"), "");

            assertThat(SetHomePluginFile.locate(plugins, moduleData))
                    .contains(moduleData.resolve("sethome-homes.yml"));
        }

        @Test
        @DisplayName("nothing anywhere is nothing found, not an exception")
        void nothingFound() throws IOException {
            Path plugins = Files.createDirectory(directory.resolve("plugins"));
            Path moduleData = Files.createDirectory(plugins.resolve("RainsHomes"));

            assertThat(SetHomePluginFile.locate(plugins, moduleData)).isEmpty();
            assertThat(SetHomePluginFile.locate(null, null)).isEmpty();
        }

        @Test
        @DisplayName("the expected place is preferred over a copy dropped elsewhere")
        void expectedPlaceWinsOverFallbacks() throws IOException {
            Path plugins = Files.createDirectory(directory.resolve("plugins"));
            Path setHome = Files.createDirectory(plugins.resolve("SetHome"));
            Files.writeString(setHome.resolve("homes.yml"), "real");
            Path moduleData = Files.createDirectory(plugins.resolve("RainsHomes"));
            Files.writeString(moduleData.resolve("sethome-homes.yml"), "decoy");

            assertThat(SetHomePluginFile.locate(plugins, moduleData))
                    .contains(setHome.resolve("homes.yml"));
        }
    }

    @Nested
    @DisplayName("against a real server's export")
    class LiveExport {

        /**
         * Copied verbatim from an actual {@code plugins/SetHome/homes.yml} — 21 players, 144 homes
         * across three worlds, none of it written to make this test pass.
         */
        private Path liveFile() throws IOException {
            Path file = directory.resolve("live-homes.yml");
            try (InputStream in = getClass().getResourceAsStream("live-sethome-homes.yml")) {
                assertThat(in).as("the live fixture is missing").isNotNull();
                Files.copy(in, file);
            }
            return file;
        }

        @Test
        @DisplayName("every home on the real server is read, none dropped, none invented")
        void liveExport() throws IOException {
            List<LegacyHomesFile.Entry> found = SetHomePluginFile.read(liveFile());

            assertThat(found).hasSize(144);
            assertThat(found.stream().map(LegacyHomesFile.Entry::owner).collect(Collectors.toSet()))
                    .hasSize(21);
        }

        @Test
        @DisplayName("every entry landed on a name the rule accepts and a world that is not blank")
        void everyEntryIsWellFormed() throws IOException {
            List<LegacyHomesFile.Entry> found = SetHomePluginFile.read(liveFile());

            assertThat(found).allSatisfy(entry -> {
                assertThat(entry.name()).matches("[a-z0-9_-]{1,16}");
                assertThat(entry.world()).isNotBlank();
            });
        }

        @Test
        @DisplayName("a known home from the export lands with its exact coordinates")
        void aKnownHomeSurvivesExactly() throws IOException {
            LegacyHomesFile.Entry endermanfarm = SetHomePluginFile.read(liveFile()).stream()
                    .filter(entry -> entry.owner().equals(
                            UUID.fromString("c9e2c821-7b5e-44e9-a5f7-7554a4da0120"))
                            && entry.name().equals("endermanfarm"))
                    .findFirst().orElseThrow();

            assertThat(endermanfarm.world()).isEqualTo("TTV_the_end");
            assertThat(endermanfarm.x()).isEqualTo(232.23447919495388);
            assertThat(endermanfarm.y()).isEqualTo(1.0);
            assertThat(endermanfarm.z()).isEqualTo(-36.06683434394276);
        }

        @Test
        @DisplayName("the worlds on the export are exactly the three the server actually has")
        void worldsMatch() throws IOException {
            Set<String> worlds = SetHomePluginFile.read(liveFile()).stream()
                    .map(LegacyHomesFile.Entry::world)
                    .collect(Collectors.toSet());

            assertThat(worlds).containsExactlyInAnyOrder("TTV", "TTV_the_end", "TTV_nether");
        }
    }
}
