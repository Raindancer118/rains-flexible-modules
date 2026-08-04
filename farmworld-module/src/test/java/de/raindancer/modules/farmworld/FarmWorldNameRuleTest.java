package de.raindancer.modules.farmworld;

import de.raindancer.modules.farmworld.rules.FarmWorldNameRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * What a farm world may be called.
 *
 * <h2>Why the dangerous half is checked by asking Core</h2>
 * A farm world's name is a folder name and regenerating deletes that folder, so the names that must never be
 * accepted — {@code world}, anything with a slash, anything that climbs out with {@code ..} — are refused by
 * {@code WorldSet}'s own constructor. That check is the one standing between a typed command and a deleted
 * server, and this rule deliberately does not carry a second copy of it: two copies is one that can be more
 * permissive, and the more permissive of two answers is the one that runs.
 *
 * <p>So what is tested here is that the rule <em>asks</em>, that it asks without throwing, and that the words
 * {@code /farm} reads as instructions are refused too.
 */
class FarmWorldNameRuleTest {

    private final FarmWorldNameRule rule = new FarmWorldNameRule();

    @Nested
    @DisplayName("names that are fine")
    class Allowed {

        @Test
        @DisplayName("the ordinary ones")
        void theOrdinaryOnes() {
            for (String name : java.util.List.of("mining", "farm", "farmworld", "resource_world",
                    "donor-world", "fw2")) {
                assertThat(rule.check(name).isFine()).as("%s should be allowed", name).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("names that are refused, and each says which")
    class Refused {

        @Test
        @DisplayName("nothing at all")
        void empty() {
            assertThat(rule.check(null)).isEqualTo(FarmWorldNameRule.Verdict.EMPTY);
            assertThat(rule.check("   ")).isEqualTo(FarmWorldNameRule.Verdict.EMPTY);
        }

        @Test
        @DisplayName("too long to fit on its own button")
        void tooLong() {
            String tooLong = "a".repeat(FarmWorldNameRule.LONGEST + 1);

            assertThat(rule.check(tooLong)).isEqualTo(FarmWorldNameRule.Verdict.TOO_LONG);
            assertThat(rule.check("a".repeat(FarmWorldNameRule.LONGEST)).isFine()).isTrue();
        }

        @Test
        @DisplayName("characters that cannot be a folder name")
        void badCharacters() {
            for (String name : java.util.List.of("my farm", "farm!", "farm.world", "farm/world",
                    "farm\\world")) {
                assertThat(rule.check(name))
                        .as("%s should be refused for its characters", name)
                        .isEqualTo(FarmWorldNameRule.Verdict.BAD_CHARACTERS);
            }
        }

        @Test
        @DisplayName("a capital is refused rather than quietly lower-cased")
        void capitalsAreRefused() {
            // Core would accept it and lower-case it, which is worse than refusing: a farm world an admin
            // created as Mining and then cannot find under that name looks like a command that did nothing.
            assertThat(rule.check("Mining"))
                    .isEqualTo(FarmWorldNameRule.Verdict.BAD_CHARACTERS);
        }

        @Test
        @DisplayName("a word the command reads as an instruction")
        void reserved() {
            for (String word : FarmWorldNameRule.RESERVED) {
                assertThat(rule.check(word))
                        .as("/farm %s is read as an instruction, so a farm world of that name could never "
                                + "be reached by typing", word)
                        .isEqualTo(FarmWorldNameRule.Verdict.RESERVED);
            }
        }

        @Test
        @DisplayName("a reserved word in capitals too, because the command matches that way")
        void reservedIsCaseInsensitive() {
            // /farm LIST is still the list. Refused for its characters first here, which is the same answer
            // arriving by a different route — what matters is that it is not accepted.
            assertThat(rule.check("LIST").isFine()).isFalse();
        }

        @Test
        @DisplayName("one of the server's own worlds — the refusal that stands in front of a deleted server")
        void dangerous() {
            for (String name : java.util.List.of("world", "world_nether", "world_the_end", "plugins",
                    "logs", "cache")) {
                assertThat(rule.check(name))
                        .as("regenerating a farm world called %s would delete the server", name)
                        .isEqualTo(FarmWorldNameRule.Verdict.DANGEROUS);
            }
        }

        @Test
        @DisplayName("every refusal names a message, so none of them is a silent no")
        void everyRefusalHasWording() {
            for (FarmWorldNameRule.Verdict verdict : FarmWorldNameRule.Verdict.values()) {
                if (verdict.isFine()) {
                    assertThat(verdict.messageKey()).isNull();
                    continue;
                }
                assertThat(verdict.messageKey())
                        .as("%s has no wording, so it would be a refusal that says nothing", verdict)
                        .isNotNull()
                        .startsWith("farmworlds.");
            }
        }
    }

    @Nested
    @DisplayName("asking Core rather than copying it")
    class AskingCore {

        @Test
        @DisplayName("the question is asked, and never by throwing")
        void itNeverThrows() {
            // A rule is asked by a screen to decide whether to grey a button. One that threw would take the page
            // down instead of greying anything, which is why WorldSet's exception is caught here rather than
            // allowed to escape.
            assertThatCode(() -> rule.check("world")).doesNotThrowAnyException();
            assertThatCode(() -> rule.wouldCoreAllow("../../etc")).doesNotThrowAnyException();
            assertThat(rule.wouldCoreAllow("world")).isFalse();
            assertThat(rule.wouldCoreAllow("mining")).isTrue();
        }
    }

    @Nested
    @DisplayName("how many there may be")
    class HowMany {

        @Test
        @DisplayName("room until the ceiling, and then a refusal that says so")
        void theCeilingBites() {
            assertThat(rule.isRoomFor(0)).isTrue();
            assertThat(rule.isRoomFor(FarmWorldNameRule.MOST - 1)).isTrue();
            assertThat(rule.isRoomFor(FarmWorldNameRule.MOST)).isFalse();
        }

        @Test
        @DisplayName("the ceiling is modest, because each farm world is up to three worlds")
        void theCeilingIsModest() {
            // Forty farm worlds is forty worlds' worth of memory and disk, and nothing about typing the fortieth
            // create would have said so.
            assertThat(FarmWorldNameRule.MOST).isBetween(2, 16);
        }
    }
}
