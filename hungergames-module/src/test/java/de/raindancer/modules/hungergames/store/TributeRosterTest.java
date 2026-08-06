package de.raindancer.modules.hungergames.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sign-up sheet, as a file.
 *
 * <h2>What this is for</h2>
 * A tournament's tributes are decided before the evening and most of them have never been on the server. The
 * tribute screen's picker offers everybody the server has <em>seen</em>, which is precisely the wrong set, and
 * {@code /allow} is one command per person typed forty times. So: a file, reloadable while the server runs.
 */
class TributeRosterTest {

    @TempDir
    Path folder;

    private TributeRoster roster;

    private TributeRoster on(String contents) throws IOException {
        Path file = folder.resolve(TributeRoster.FILE_NAME);
        Files.writeString(file, contents);
        return new TributeRoster(file);
    }

    @Nested
    @DisplayName("reading it")
    class Reading {

        @Test
        @DisplayName("a pasted list of names becomes a list of tributes")
        void namesAreRead() throws IOException {
            roster = on("tributes:\n  - Katniss\n  - Peeta\n  - Rue\n");

            assertThat(roster.load().found())
                    .extracting(TributeRoster.Entry::name)
                    .containsExactly("Katniss", "Peeta", "Rue");
        }

        @Test
        @DisplayName("a missing file is not a problem, it is an empty sheet")
        void noFileIsNoProblem() {
            roster = new TributeRoster(folder.resolve("not-there.yml"));

            assertThat(roster.load().found()).isEmpty();
            assertThat(roster.load().problems())
                    .as("a server that has not written one yet has done nothing wrong")
                    .isEmpty();
        }

        @Test
        @DisplayName("the same person twice is one tribute, whatever the capitals")
        void duplicatesAreOnePerson() throws IOException {
            // A sheet written by three people. These are one person.
            roster = on("tributes:\n  - Katniss\n  - katniss\n  - KATNISS\n");
            TributeRoster.Report report = roster.load();

            assertThat(report.found()).hasSize(1);
            assertThat(report.problems())
                    .as("said out loud, because a list that silently shrank is one somebody recounts")
                    .isNotEmpty();
        }

        @Test
        @DisplayName("a pasted Discord line is skipped and reported, not registered")
        void rubbishIsRefused() throws IOException {
            // The realistic mistake: copying a nickname column rather than a name column.
            roster = on("tributes:\n  - Katniss\n  - \"Peeta (he/him)\"\n  - x\n");
            TributeRoster.Report report = roster.load();

            assertThat(report.found()).extracting(TributeRoster.Entry::name).containsExactly("Katniss");
            assertThat(report.problems())
                    .as("a tribute nobody can ever match to a player sits in the register looking like "
                            + "somebody who has not turned up yet")
                    .hasSize(2);
        }

        @Test
        @DisplayName("blank lines in a pasted block are ignored quietly")
        void blanksAreNotWorthMentioning() throws IOException {
            roster = on("tributes:\n  - Katniss\n  - ''\n  - Peeta\n");
            TributeRoster.Report report = roster.load();

            assertThat(report.found()).hasSize(2);
            assertThat(report.problems())
                    .as("reporting a blank line would bury the problems that matter")
                    .isEmpty();
        }

        @Test
        @DisplayName("a file that is not YAML is reported rather than thrown")
        void brokenYamlIsReported() throws IOException {
            roster = on("tributes: [unclosed\n  : :\n");
            TributeRoster.Report report = roster.load();

            // This is called from a button during a tournament. A page that will not open is worse than a
            // page saying the file has a typo in it.
            assertThat(report.found()).isEmpty();
            assertThat(report.problems()).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("who somebody is")
    class Identity {

        @Test
        @DisplayName("a name means the same tribute however it was registered")
        void theDerivationMatchesAllow() {
            // /allow derives the same id from the same name. If these ever disagree, a name added to the file
            // and then allowed by command is two tributes, and one of them can never be matched to a player.
            assertThat(TributeRoster.derivedIdFor("Katniss"))
                    .isEqualTo(java.util.UUID.nameUUIDFromBytes(
                            "hungergames:katniss".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }

        @Test
        @DisplayName("capitals and spaces do not make a second person")
        void theDerivationIsForgiving() {
            assertThat(TributeRoster.derivedIdFor("  KATNISS "))
                    .isEqualTo(TributeRoster.derivedIdFor("katniss"));
        }

        @Test
        @DisplayName("what counts as a name")
        void theNameRule() {
            assertThat(TributeRoster.isPlausibleName("Katniss")).isTrue();
            assertThat(TributeRoster.isPlausibleName("a_b_1")).isTrue();
            assertThat(TributeRoster.isPlausibleName("xy")).as("too short").isFalse();
            assertThat(TributeRoster.isPlausibleName("x".repeat(17))).as("too long").isFalse();
            assertThat(TributeRoster.isPlausibleName("Katniss (she/her)")).as("a nickname").isFalse();
            assertThat(TributeRoster.isPlausibleName(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("writing to it")
    class Writing {

        @Test
        @DisplayName("a name typed into the screen is also put on the sheet")
        void whatIsTypedIsRemembered() throws IOException {
            roster = on("tributes: []\n");

            assertThat(roster.remember("Katniss")).isTrue();
            assertThat(roster.load().found()).extracting(TributeRoster.Entry::name)
                    .as("otherwise the file and the register disagree the first time somebody uses both, and "
                            + "reading the file afterwards looks as though it has forgotten people")
                    .containsExactly("Katniss");
        }

        @Test
        @DisplayName("adding somebody who is already on it changes nothing")
        void noDuplicatesAreWritten() throws IOException {
            roster = on("tributes:\n  - Katniss\n");

            roster.remember("katniss");

            assertThat(roster.load().found()).hasSize(1);
        }

        @Test
        @DisplayName("a name that is not a name is not written")
        void rubbishIsNotRemembered() throws IOException {
            roster = on("tributes: []\n");

            assertThat(roster.remember("Katniss (she/her)")).isFalse();
            assertThat(roster.load().found()).isEmpty();
        }

        @Test
        @DisplayName("an absent file is created with an explanation in it")
        void theShippedFileExplainsItself() throws IOException {
            roster = new TributeRoster(folder.resolve("fresh.yml"));

            assertThat(roster.createIfMissing()).isTrue();
            String written = Files.readString(folder.resolve("fresh.yml"));

            assertThat(written)
                    .as("somebody who goes looking for this file has to be able to use it without asking")
                    .contains("one name per line")
                    .contains("tributes: []");
            assertThat(written)
                    .as("an example name is a tribute somebody forgets to delete, and Steve turning up on a "
                            + "real whitelist is a confusing five minutes")
                    .doesNotContain("\n  - ");
        }

        @Test
        @DisplayName("an existing file is never overwritten")
        void somebodysListIsSafe() throws IOException {
            roster = on("tributes:\n  - Katniss\n");

            assertThat(roster.createIfMissing()).isFalse();
            assertThat(roster.load().found()).hasSize(1);
        }
    }
}
