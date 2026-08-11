package de.raindancer.modules.homes;

import de.raindancer.modules.homes.store.SetHomeConfigFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading the settings a server that used to run the third-party {@code SetHome} plugin had chosen.
 */
class SetHomeConfigFileTest {

    @TempDir
    Path directory;

    private Path fileWith(String body) throws IOException {
        Path file = directory.resolve("config.yml");
        Files.writeString(file, body);
        return file;
    }

    @Nested
    @DisplayName("reading what is there")
    class Reading {

        @Test
        @DisplayName("every field SetHome had lands where it belongs")
        void everyFieldLands() throws IOException {
            Path file = fileWith("""
                    cooldown: 42
                    max-homes:
                      default: 15
                    cancel-on-move: true
                    play-sound: false
                    """);

            SetHomeConfigFile.Values values = SetHomeConfigFile.read(file).orElseThrow();

            assertThat(values.cooldownSeconds()).isEqualTo(42);
            assertThat(values.maxHomes()).isEqualTo(15);
            assertThat(values.cancelOnMove()).isTrue();
            assertThat(values.playSound()).isFalse();
        }

        @Test
        @DisplayName("SetHome's real config, exported from the live server, reads as (0, 15, false, true)")
        void theActualExport() throws IOException {
            // What /plugins/SetHome/config.yml actually held.
            Path file = fileWith("""
                    cooldown: 0

                    max-homes:
                      default: 15

                    cancel-on-move: false

                    play-sound: true
                    """);

            SetHomeConfigFile.Values values = SetHomeConfigFile.read(file).orElseThrow();

            assertThat(values.cooldownSeconds()).isZero();
            assertThat(values.maxHomes()).isEqualTo(15);
            assertThat(values.cancelOnMove()).isFalse();
            assertThat(values.playSound()).isTrue();
        }
    }

    @Nested
    @DisplayName("a file that is not quite right")
    class Broken {

        @Test
        @DisplayName("no file at all is nothing to read, not a failure")
        void noFile() {
            assertThat(SetHomeConfigFile.read(directory.resolve("nothing.yml"))).isEmpty();
            assertThat(SetHomeConfigFile.read(null)).isEmpty();
        }

        @Test
        @DisplayName("an empty file falls back to SetHome's own shipped defaults")
        void emptyFileFallsBackToDefaults() throws IOException {
            SetHomeConfigFile.Values values = SetHomeConfigFile.read(fileWith("")).orElseThrow();

            assertThat(values.cooldownSeconds()).isZero();
            assertThat(values.maxHomes()).isEqualTo(15);
            assertThat(values.cancelOnMove()).isFalse();
            assertThat(values.playSound()).isTrue();
        }

        @Test
        @DisplayName("a file that is not YAML at all is nothing to read, not a crash")
        void rubbishIsNotFatal() throws IOException {
            assertThat(SetHomeConfigFile.read(fileWith("this: is: not: yaml:\n\t "))).isEmpty();
        }

        @Test
        @DisplayName("a negative cooldown or limit is floored at zero rather than kept negative")
        void negativeNumbersAreFloored() throws IOException {
            Path file = fileWith("""
                    cooldown: -5
                    max-homes:
                      default: -1
                    """);

            SetHomeConfigFile.Values values = SetHomeConfigFile.read(file).orElseThrow();

            assertThat(values.cooldownSeconds()).isZero();
            assertThat(values.maxHomes()).isZero();
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
            Files.writeString(setHome.resolve("config.yml"), "");
            Path moduleData = Files.createDirectory(plugins.resolve("RainsHomes"));

            assertThat(SetHomeConfigFile.locate(plugins, moduleData))
                    .contains(setHome.resolve("config.yml"));
        }

        @Test
        @DisplayName("nothing anywhere is nothing found, not an exception")
        void nothingFound() throws IOException {
            Path plugins = Files.createDirectory(directory.resolve("plugins"));
            Path moduleData = Files.createDirectory(plugins.resolve("RainsHomes"));

            assertThat(SetHomeConfigFile.locate(plugins, moduleData)).isEmpty();
        }
    }

    @Nested
    @DisplayName("setting the file aside")
    class SettingAside {

        @Test
        @DisplayName("the file is renamed, not deleted, so a second start does not read it again")
        void fileIsRenamed() throws IOException {
            Path file = fileWith("cooldown: 0\n");

            SetHomeConfigFile.setAside(file, null);

            assertThat(Files.exists(file)).isFalse();
            assertThat(Files.exists(file.resolveSibling(file.getFileName() + ".imported"))).isTrue();
        }
    }
}
