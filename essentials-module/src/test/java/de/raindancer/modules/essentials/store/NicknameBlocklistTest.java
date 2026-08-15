package de.raindancer.modules.essentials.store;

import de.raindancer.modules.essentials.rules.NicknameRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class NicknameBlocklistTest {

    private static NicknameBlocklist writtenAs(Path folder, String yaml) {
        try {
            Path file = folder.resolve("blocklist.yml");
            Files.writeString(file, yaml);
            NicknameBlocklist blocklist = new NicknameBlocklist(file, () -> null);
            blocklist.load();
            return blocklist;
        } catch (IOException failure) {
            throw new AssertionError("could not write a test blocklist", failure);
        }
    }

    @Nested
    @DisplayName("matching")
    class Matching {

        @Test
        @DisplayName("an enabled report section matches its names")
        void reportSection(@TempDir Path folder) {
            NicknameBlocklist blocklist = writtenAs(folder, """
                    politicians:
                      enabled: true
                      action: report
                      names:
                        - donald trump
                    """);

            assertThat(blocklist.matchOf("donald trump"))
                    .isEqualTo(NicknameRule.BlockMatch.REPORTED);
        }

        @Test
        @DisplayName("an enabled ban section matches its names")
        void banSection(@TempDir Path folder) {
            NicknameBlocklist blocklist = writtenAs(folder, """
                    hate-figures:
                      enabled: true
                      action: ban
                      names:
                        - hitler
                    """);

            assertThat(blocklist.matchOf("hitler")).isEqualTo(NicknameRule.BlockMatch.BANNED);
        }

        @Test
        @DisplayName("case-insensitive")
        void caseInsensitive(@TempDir Path folder) {
            NicknameBlocklist blocklist = writtenAs(folder, """
                    politicians:
                      enabled: true
                      action: report
                      names:
                        - donald trump
                    """);

            assertThat(blocklist.matchOf("DONALD TRUMP"))
                    .isEqualTo(NicknameRule.BlockMatch.REPORTED);
        }

        @Test
        @DisplayName("a disabled section matches nothing")
        void disabledSection(@TempDir Path folder) {
            NicknameBlocklist blocklist = writtenAs(folder, """
                    politicians:
                      enabled: false
                      action: report
                      names:
                        - donald trump
                    """);

            assertThat(blocklist.matchOf("donald trump"))
                    .isEqualTo(NicknameRule.BlockMatch.NONE);
        }

        @Test
        @DisplayName("a name in no section at all is not a match")
        void unlistedName(@TempDir Path folder) {
            NicknameBlocklist blocklist = writtenAs(folder, """
                    politicians:
                      enabled: true
                      action: report
                      names:
                        - donald trump
                    """);

            assertThat(blocklist.matchOf("nobody in particular"))
                    .isEqualTo(NicknameRule.BlockMatch.NONE);
        }

        @Test
        @DisplayName("a ban in one section beats a report in another for the same name")
        void banBeatsReportAcrossSections(@TempDir Path folder) {
            NicknameBlocklist blocklist = writtenAs(folder, """
                    politicians:
                      enabled: true
                      action: report
                      names:
                        - both
                    hate-figures:
                      enabled: true
                      action: ban
                      names:
                        - both
                    """);

            assertThat(blocklist.matchOf("both")).isEqualTo(NicknameRule.BlockMatch.BANNED);
        }
    }

    @Test
    @DisplayName("an unreadable action defaults to report rather than refusing to load")
    void unknownActionDefaultsToReport(@TempDir Path folder) {
        NicknameBlocklist blocklist = writtenAs(folder, """
                mystery:
                  enabled: true
                  action: something-else
                  names:
                    - whoever
                """);

        assertThat(blocklist.matchOf("whoever")).isEqualTo(NicknameRule.BlockMatch.REPORTED);
    }

    @Test
    @DisplayName("a file that does not exist yet is written from the bundled default")
    void writesTheDefaultOnFirstRun(@TempDir Path folder) throws IOException {
        Path file = folder.resolve("blocklist.yml");
        String bundled = """
                seed-section:
                  enabled: true
                  action: report
                  names:
                    - seeded name
                """;

        NicknameBlocklist blocklist = new NicknameBlocklist(file,
                () -> new ByteArrayInputStream(bundled.getBytes(StandardCharsets.UTF_8)));
        blocklist.load();

        assertThat(Files.exists(file)).isTrue();
        assertThat(blocklist.matchOf("seeded name")).isEqualTo(NicknameRule.BlockMatch.REPORTED);
    }

    @Test
    @DisplayName("a file that already exists is never overwritten by the bundled default")
    void neverOverwritesAnExistingFile(@TempDir Path folder) throws IOException {
        Path file = folder.resolve("blocklist.yml");
        Files.writeString(file, """
                owners-own-section:
                  enabled: true
                  action: ban
                  names:
                    - their own choice
                """);

        NicknameBlocklist blocklist = new NicknameBlocklist(file,
                () -> new ByteArrayInputStream("should never be written".getBytes(
                        StandardCharsets.UTF_8)));
        blocklist.load();

        assertThat(blocklist.matchOf("their own choice"))
                .isEqualTo(NicknameRule.BlockMatch.BANNED);
        assertThat(Files.readString(file)).contains("owners-own-section");
    }

    @Test
    @DisplayName("enabledNameCount only counts names in sections that are switched on")
    void enabledNameCountIgnoresDisabledSections(@TempDir Path folder) {
        NicknameBlocklist blocklist = writtenAs(folder, """
                switched-on:
                  enabled: true
                  action: report
                  names:
                    - one
                    - two
                switched-off:
                  enabled: false
                  action: report
                  names:
                    - three
                    - four
                    - five
                """);

        assertThat(blocklist.enabledNameCount()).isEqualTo(2);
    }
}
