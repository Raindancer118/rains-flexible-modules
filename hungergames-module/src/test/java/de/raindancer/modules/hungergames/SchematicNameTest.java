package de.raindancer.modules.hungergames;

import de.raindancer.modules.hungergames.visual.SchematicName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the schematic-name guard refuses what it is there to refuse.
 *
 * <p>The plugin this was ported from had this guard and no test for it, because the guard could not be reached
 * without a running server and a data folder — it was a canonical-path comparison inside the class that opened
 * the file. Pulling the decision out into a pure function is what makes the list below writable, and the list
 * is the point: a traversal guard is only as good as the shapes somebody thought to try.
 */
class SchematicNameTest {

    @Nested
    @DisplayName("the names this module actually uses")
    class Allowed {

        @Test
        @DisplayName("every schematic this module ships is accepted")
        void theRealOnesPass() {
            // Read from the same list the arena builds from. A guard that refused one of these would be found
            // at /init, in front of everybody, rather than here.
            for (String name : new String[]{"platform.schem", "platformbarrier.schem", "tube.schem",
                    "middle.schem", "middlelake.schem", "fuellhorn.schem", "fuellhorn.nbt"}) {
                assertThat(SchematicName.checked(name))
                        .as("%s is one of the files this module ships", name)
                        .contains(name);
            }
        }

        @Test
        @DisplayName("surrounding whitespace is trimmed rather than refused")
        void whitespaceIsTrimmed() {
            // These arrive from config files and typed commands, where a trailing space is a typo and not an
            // attack. Trimmed, so the typo is not a broken arena.
            assertThat(SchematicName.checked("  platform.schem  ")).contains("platform.schem");
        }

        @Test
        @DisplayName("dashes and underscores are fine")
        void ordinaryPunctuationIsFine() {
            assertThat(SchematicName.isSafe("my-arena_2.schem")).isTrue();
        }
    }

    @Nested
    @DisplayName("what escapes the folder")
    class Traversal {

        @Test
        @DisplayName("dot-dot in any position is refused")
        void dotDotIsRefused() {
            assertThat(SchematicName.isSafe("../secrets.yml")).isFalse();
            assertThat(SchematicName.isSafe("..\\secrets.yml")).isFalse();
            assertThat(SchematicName.isSafe("a/../../b.schem")).isFalse();
            assertThat(SchematicName.isSafe("....schem")).isFalse();
        }

        @Test
        @DisplayName("a separator is refused even without a dot-dot")
        void separatorsAreRefused() {
            // Subfolders are not a feature here, and allowing one is what makes the dot-dot check the only
            // thing standing between a name and the rest of the disk.
            assertThat(SchematicName.isSafe("nested/platform.schem")).isFalse();
            assertThat(SchematicName.isSafe("nested\\platform.schem")).isFalse();
        }

        @Test
        @DisplayName("an absolute path is refused")
        void absolutePathsAreRefused() {
            assertThat(SchematicName.isSafe("/etc/passwd")).isFalse();
            assertThat(SchematicName.isSafe("/home/tom/secret.schem")).isFalse();
        }

        @Test
        @DisplayName("a Windows drive letter is refused, which a dot-dot check alone would miss")
        void driveLettersAreRefused() {
            // The specific hole in a guard written only against ".." and "/": neither appears here.
            assertThat(SchematicName.isSafe("C:secret.schem")).isFalse();
            assertThat(SchematicName.isSafe("C:\\Windows\\win.ini")).isFalse();
        }

        @Test
        @DisplayName("a null byte is refused, so what was checked is what gets opened")
        void nullBytesAreRefused() {
            // Historically how a validated name and an opened file come apart: some layers stop at the byte
            // and some keep going, so "platform.schem\0../../x" passes a suffix check and opens something else.
            assertThat(SchematicName.isSafe("platform.schem\0")).isFalse();
            assertThat(SchematicName.isSafe("platform\0.schem")).isFalse();
        }
    }

    @Nested
    @DisplayName("shapes that are simply not names")
    class Nonsense {

        @Test
        @DisplayName("null, empty and blank are refused")
        void emptyIsRefused() {
            assertThat(SchematicName.checked(null)).isEmpty();
            assertThat(SchematicName.checked("")).isEmpty();
            assertThat(SchematicName.checked("   ")).isEmpty();
        }

        @Test
        @DisplayName("an over-long name is refused rather than truncated")
        void tooLongIsRefused() {
            // Truncating would turn one name into another that exists, which is worse than a refusal.
            assertThat(SchematicName.isSafe("a".repeat(100) + ".schem")).isFalse();
        }

        @Test
        @DisplayName("a name with no extension is refused")
        void anExtensionIsRequired() {
            assertThat(SchematicName.isSafe("platform")).isFalse();
        }

        @Test
        @DisplayName("anything outside the allow-list is refused")
        void theAllowListIsAnAllowList() {
            // A deny-list is a list somebody has to have thought of everything for. These are files this
            // project ships; they do not need spaces, quotes, semicolons or percent signs.
            assertThat(SchematicName.isSafe("plat form.schem")).isFalse();
            assertThat(SchematicName.isSafe("platform;rm.schem")).isFalse();
            assertThat(SchematicName.isSafe("platform%2e%2e.schem")).isFalse();
            assertThat(SchematicName.isSafe("platform'.schem")).isFalse();
            assertThat(SchematicName.isSafe("$(whoami).schem")).isFalse();
        }

        @Test
        @DisplayName("a double extension is refused")
        void oneExtensionOnly() {
            assertThat(SchematicName.isSafe("platform.schem.yml")).isFalse();
        }
    }
}
