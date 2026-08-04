package de.raindancer.modules.pack;

import de.raindancer.modules.pack.rules.PublishedHashRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading a pack's hash out of the file its host publishes.
 *
 * <p>The one piece of real logic in this module, and the reason it is a rule rather than three lines
 * inside the fetch: every failure here produces a hash that <em>looks</em> perfectly valid and is for
 * the wrong file, which the client answers by silently never applying the pack.
 */
class PublishedHashRuleTest {

    private final PublishedHashRule rule = new PublishedHashRule();

    /** What the pack host actually serves, verbatim. */
    private static final String REAL =
            "9da2d07b71bf028fd9da9e9260facf2e52916b63  yeukpack.zip\n"
            + "33d179b55dcf202fe06173382a3e4ffa352e7c72  yeukpack-datapack.zip\n";

    @Nested
    @DisplayName("the file this server actually uses")
    class TheRealFile {

        @Test
        @DisplayName("the pack's own hash is found")
        void findsThePack() {
            assertThat(rule.hashOf(REAL, "yeukpack.zip"))
                    .contains("9da2d07b71bf028fd9da9e9260facf2e52916b63");
        }

        @Test
        @DisplayName("the datapack listed beside it is not mistaken for the pack")
        void doesNotTakeTheFirstLine() {
            // The mistake this rule exists for. Taking the first line gives a server the datapack's
            // hash for its resource pack — a valid sha1, accepted everywhere, and a pack that never
            // applies with nothing anywhere saying why.
            assertThat(rule.hashOf(REAL, "yeukpack-datapack.zip"))
                    .contains("33d179b55dcf202fe06173382a3e4ffa352e7c72");
        }

        @Test
        @DisplayName("a file that is not listed answers nothing, rather than a guess")
        void unlistedIsEmpty() {
            assertThat(rule.hashOf(REAL, "somethingelse.zip")).isEmpty();
        }
    }

    @Nested
    @DisplayName("the shapes sha1sum writes")
    class Formats {

        @Test
        @DisplayName("binary mode marks the name with a star")
        void binaryMode() {
            assertThat(rule.hashOf("9da2d07b71bf028fd9da9e9260facf2e52916b63 *yeukpack.zip",
                    "yeukpack.zip")).contains("9da2d07b71bf028fd9da9e9260facf2e52916b63");
        }

        @Test
        @DisplayName("a path is the same file as its name")
        void aPathIsTheSameFile() {
            assertThat(rule.hashOf("9da2d07b71bf028fd9da9e9260facf2e52916b63  files/yeukpack.zip",
                    "yeukpack.zip")).contains("9da2d07b71bf028fd9da9e9260facf2e52916b63");
        }

        @Test
        @DisplayName("a capitalised hash is read, and comes back lower case")
        void capitalsAreNormalised() {
            // A client compares the hash it computes against the one it was sent, and the case it
            // arrives in depends on which tool wrote the file.
            assertThat(rule.hashOf("9DA2D07B71BF028FD9DA9E9260FACF2E52916B63  yeukpack.zip",
                    "yeukpack.zip")).contains("9da2d07b71bf028fd9da9e9260facf2e52916b63");
        }

        @Test
        @DisplayName("either kind of line ending")
        void lineEndings() {
            assertThat(rule.hashOf(REAL.replace("\n", "\r\n"), "yeukpack-datapack.zip"))
                    .contains("33d179b55dcf202fe06173382a3e4ffa352e7c72");
        }
    }

    @Nested
    @DisplayName("everything that is not a hash")
    class Refusals {

        @Test
        @DisplayName("something that is not a sha1 is not returned as one")
        void notASha1() {
            // A host serving an error page instead of the file. Passed on, it becomes a pack the
            // client rejects — so the honest answer is that there is no hash.
            assertThat(rule.hashOf("<html>404 Not Found</html>", "yeukpack.zip")).isEmpty();
            assertThat(rule.hashOf("nope  yeukpack.zip", "yeukpack.zip")).isEmpty();
            assertThat(rule.hashOf("9da2d07b71bf  yeukpack.zip", "yeukpack.zip"))
                    .as("too short is not a sha1")
                    .isEmpty();
        }

        @Test
        @DisplayName("a line with no file name on it is skipped")
        void noFileName() {
            assertThat(rule.hashOf("9da2d07b71bf028fd9da9e9260facf2e52916b63", "yeukpack.zip"))
                    .isEmpty();
        }

        @Test
        @DisplayName("nothing at all answers nothing, and never throws")
        void nothingIsFine() {
            assertThat(rule.hashOf(null, "yeukpack.zip")).isEmpty();
            assertThat(rule.hashOf("", "yeukpack.zip")).isEmpty();
            assertThat(rule.hashOf(REAL, null)).isEmpty();
            assertThat(rule.hashOf(REAL, "  ")).isEmpty();
        }

        @Test
        @DisplayName("a good line after a bad one is still found")
        void oneBadLineIsNotTheEnd() {
            assertThat(rule.hashOf("garbage\n\n9da2d07b71bf028fd9da9e9260facf2e52916b63  yeukpack.zip",
                    "yeukpack.zip")).contains("9da2d07b71bf028fd9da9e9260facf2e52916b63");
        }
    }
}
