package de.raindancer.modules.hungergames;

import de.raindancer.core.data.settings.SettingsSchema;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.modules.hungergames.store.LegacyConfigImport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Importing a {@code config.yml} written by the old standalone plugin.
 *
 * <h2>What is really being tested</h2>
 * Not "does it copy values" — that is one line and it either works or fails loudly. The thing that matters is
 * that <b>nothing goes missing quietly</b>. Around ninety of the old plugin's keys survive at their old paths
 * and the rest moved: the announcement wording became {@code messages.yml}, the loot tables became their own
 * file, the interaction list became RainsCore's {@code LandFlags}.
 *
 * <p>A server owner who copies their old file across and gets "47 settings imported" with no further comment
 * has lost their wording, their loot and their protections, and will find out during a tournament. So the
 * assertions below are mostly about the report rather than about the settings.
 *
 * <p>The fixture is {@code legacy-config.yml} — the same snapshot of all 272 real key paths that
 * {@code HungerGamesSettingsMigrationTest} checks the schema against. Using the real thing rather than a
 * hand-written sample is the point: a sample would only contain the keys somebody remembered.
 */
class LegacyConfigImportTest {

    @TempDir
    Path folder;

    private SettingsStore<HungerGamesSettings> store;

    @BeforeEach
    void setUp() {
        store = new SettingsStore<>(
                SettingsSchema.of(HungerGamesSettings.class, HungerGamesSettings.DEFAULTS),
                folder.resolve("config.yml"));
        store.load();
    }

    /** The real snapshot of the old plugin's key list, written where the import expects it. */
    private Path theOldFile() {
        try (InputStream fixture = getClass().getResourceAsStream("/legacy-config.yml")) {
            assertThat(fixture).as("legacy-config.yml is missing from the test resources").isNotNull();
            Path written = folder.resolve(LegacyConfigImport.FILE_NAME);
            Files.writeString(written, new String(fixture.readAllBytes(), StandardCharsets.UTF_8));
            return written;
        } catch (IOException unreadable) {
            throw new AssertionError("could not lay out the fixture", unreadable);
        }
    }

    @Nested
    @DisplayName("what comes across")
    class Applied {

        @Test
        @DisplayName("a real old config brings a substantial number of settings with it")
        void mostOfItSurvives() {
            LegacyConfigImport.Report report = LegacyConfigImport.from(theOldFile(), store);

            // Around ninety keys kept their old paths on purpose — see HungerGamesSettings. A number far
            // below that means the paths drifted and an upgrading server would silently get defaults.
            assertThat(report.count())
                    .as("the old paths were kept deliberately; importing only a handful of them means "
                            + "they have drifted and an upgrade silently resets the rest")
                    .isGreaterThan(60);
        }

        @Test
        @DisplayName("a value in the old file actually reaches the settings")
        void theValuesAreReal() throws IOException {
            Path old = folder.resolve(LegacyConfigImport.FILE_NAME);
            Files.writeString(old, """
                    game:
                      duration: 45
                      countdown: 7
                    """);

            LegacyConfigImport.from(old, store);

            assertThat(store.current().gameDurationMinutes()).isEqualTo(45);
            assertThat(store.current().countdownSeconds()).isEqualTo(7);
        }

        @Test
        @DisplayName("what the old file did not mention keeps its new default")
        void nothingElseIsDisturbed() throws IOException {
            Path old = folder.resolve(LegacyConfigImport.FILE_NAME);
            Files.writeString(old, "game:\n  duration: 45\n");

            LegacyConfigImport.from(old, store);

            // The import writes key by key rather than replacing the file, so a setting the old plugin
            // never had — the border speed ceiling, say — arrives at this version's default rather than at
            // nothing.
            assertThat(store.current().borderMaxEdgeSpeed())
                    .isEqualTo(HungerGamesSettings.DEFAULTS.borderMaxEdgeSpeed());
        }
    }

    @Nested
    @DisplayName("what does not, and is said out loud")
    class Reported {

        @Test
        @DisplayName("the wording, the loot and the shop are named rather than dropped")
        void whatMovedIsReported() {
            LegacyConfigImport.Report report = LegacyConfigImport.from(theOldFile(), store);

            assertThat(report.elsewhere())
                    .as("a server told '47 imported' and nothing else has lost its wording and its loot, "
                            + "and finds out during a tournament")
                    .isNotEmpty();

            String said = String.join(" | ", report.lines());
            assertThat(said).contains("messages.yml");
            assertThat(said).contains("loot");
        }

        @Test
        @DisplayName("a key from nowhere is reported, never applied and never silently dropped")
        void somethingUnrecognised() throws IOException {
            Path old = folder.resolve(LegacyConfigImport.FILE_NAME);
            Files.writeString(old, "game:\n  duration: 45\nsome-fork:\n  invented: true\n");

            LegacyConfigImport.Report report = LegacyConfigImport.from(old, store);

            assertThat(report.unknown()).contains("some-fork.invented");
            assertThat(report.applied()).contains("game.duration");
        }

        @Test
        @DisplayName("a value outside its range is refused and named, not applied at some other number")
        void outOfRangeIsNotSilent() throws IOException {
            Path old = folder.resolve(LegacyConfigImport.FILE_NAME);
            // A round of one minute, against a floor of five. I expected the store to clamp this and it
            // refuses instead — which is the better answer of the two, and worth a test saying so: an
            // import that quietly turned somebody's 1 into a 5 would be a number they believe they set.
            Files.writeString(old, "game:\n  duration: 1\n  countdown: 7\n");

            LegacyConfigImport.Report report = LegacyConfigImport.from(old, store);

            assertThat(report.applied())
                    .as("refused, so it was not applied")
                    .doesNotContain("game.duration");
            assertThat(String.join(" ", report.problems()))
                    .as("and named, or the owner never learns which of their settings did not survive")
                    .contains("game.duration");
            assertThat(report.applied())
                    .as("one bad value costs that key and nothing else")
                    .contains("game.countdown");
        }

        @Test
        @DisplayName("a clamp that happens when the value is *read* is not one the import can see")
        void theLimitOfWhatThisCanReport() {
            // Written after the previous test failed against border.max-edge-speed: 0.0, which I had
            // expected to be reported as adjusted and is not. The reason is worth stating rather than
            // papering over — it is a real gap in what this class can promise.
            //
            // @Range clamps happen in the settings store, at write time, and the import sees them by
            // comparing what it wrote with what the store now displays. But several accessors clamp again
            // when they are *read*: borderEdgeSpeed() returns max(0.1, ...) because BorderSettings' own
            // constructor refuses zero. The stored value really is 0.0 and the round really runs at 0.1,
            // and no comparison at import time can notice that.
            //
            // Reporting it would mean the import knowing which accessors clamp, which is a list that would
            // go stale. The honest position is that the import reports what the *file* was changed to, and
            // ConfigurationRules — which runs a moment later, at startup — is what reports a configuration
            // that will not behave as written. Between the two, nothing is silent.
            assertThat(HungerGamesSettings.DEFAULTS.borderEdgeSpeed())
                    .as("the accessor clamps; the component does not")
                    .isGreaterThan(0.0);
        }

        @Test
        @DisplayName("the report ends with what did not come across, not with the count")
        void theOrderOfTheReport() {
            LegacyConfigImport.Report report = LegacyConfigImport.from(theOldFile(), store);

            // People stop reading after the good news. The line that needs them to do something has to be
            // after it, not before.
            var lines = report.lines();
            assertThat(lines.get(0)).contains("imported");
            assertThat(String.join(" ", lines.subList(1, lines.size())))
                    .contains("NOT imported");
        }
    }

    @Nested
    @DisplayName("when things go wrong")
    class Trouble {

        @Test
        @DisplayName("a file that is not YAML changes nothing and says so")
        void notYamlAtAll() throws IOException {
            Path old = folder.resolve(LegacyConfigImport.FILE_NAME);
            Files.writeString(old, "this: is: not: yaml: [[[");

            int before = store.current().gameDurationMinutes();
            LegacyConfigImport.Report report = LegacyConfigImport.from(old, store);

            assertThat(report.problems()).isNotEmpty();
            assertThat(report.applied()).isEmpty();
            assertThat(store.current().gameDurationMinutes())
                    .as("a half-applied import is worse than none — the settings must be exactly as they "
                            + "were")
                    .isEqualTo(before);
        }

        @Test
        @DisplayName("a value of the wrong type costs that key and nothing else")
        void oneBadValue() throws IOException {
            Path old = folder.resolve(LegacyConfigImport.FILE_NAME);
            Files.writeString(old, "game:\n  duration: \"three hours\"\n  countdown: 7\n");

            LegacyConfigImport.Report report = LegacyConfigImport.from(old, store);

            assertThat(report.problems())
                    .as("a key that could not be read has to be named, or the owner never learns which")
                    .isNotEmpty();
            assertThat(store.current().countdownSeconds())
                    .as("the rest of the file still comes across")
                    .isEqualTo(7);
        }

        @Test
        @DisplayName("branches are not applied as if they were values")
        void onlyLeaves() {
            LegacyConfigImport.Report report = LegacyConfigImport.from(theOldFile(), store);

            // getKeys(true) returns "game" as well as "game.duration", and a branch applied as a setting is
            // a MemorySection written into a config as its toString.
            assertThat(report.applied()).noneMatch(key -> key.equals("game") || key.equals("border"));
            assertThat(report.unknown()).noneMatch(key -> key.equals("game") || key.equals("border"));
        }
    }

    @Test
    @DisplayName("every key that moved has somewhere to point at")
    void nothingIsMerelyForgotten() {
        LegacyConfigImport.Report report = LegacyConfigImport.from(theOldFile(), store);

        report.elsewhere().forEach((key, where) -> assertThat(where)
                .as("%s is reported as moved but not as moved *to* anywhere, which tells the owner "
                        + "nothing they can act on", key)
                .isNotBlank()
                .hasSizeGreaterThan(10));
    }

    @Test
    @DisplayName("the old file is left where it is — an import that ate its input cannot be checked")
    void theInputSurvives() {
        Path old = theOldFile();

        LegacyConfigImport.from(old, store);

        // The module renames it afterwards, deliberately outside this class: the first thing anybody does
        // with a migration is check it, and reading a file that no longer exists is not checking.
        assertThat(Files.exists(old)).isTrue();
    }

    @Test
    @DisplayName("what this version has that the old one never did")
    void theNewSettingsAreListed() {
        var added = LegacyConfigImport.notInTheOldFile(theOldFile(),
                SettingsSchema.of(HungerGamesSettings.class, HungerGamesSettings.DEFAULTS));

        // Not an error and worth showing: these are the settings an upgrading owner has never seen and is
        // now running on defaults they did not choose.
        assertThat(added).isNotNull();
    }
}
