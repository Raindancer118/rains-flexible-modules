package de.raindancer.modules.warp;

import de.raindancer.modules.warp.rules.WarpNameRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a warp may be called.
 *
 * <p>Three of these are not tidiness. A warp called {@code list} is a warp that
 * {@code /warp list} can never reach, because the command reads the first word as a subcommand
 * before it reads it as a name — which is the bug the version of this in RainsCore still has. A name
 * with a space in it cannot be typed at all. And a name longer than the button it appears on is a
 * warp people scroll past looking for.
 */
class WarpNameRuleTest {

    private final WarpNameRule rule = new WarpNameRule(24);

    @Nested
    @DisplayName("names that are fine")
    class Allowed {

        @Test
        @DisplayName("ordinary ones")
        void ordinaryNames() {
            for (String name : new String[]{"spawn", "TheMine", "shop-2", "end_portal", "a1"}) {
                assertThat(rule.check(name))
                        .as("%s is a perfectly good warp name", name)
                        .isEqualTo(WarpNameRule.Verdict.FINE);
            }
        }

        @Test
        @DisplayName("one exactly as long as the limit")
        void exactlyTheLimit() {
            assertThat(rule.check("a".repeat(24))).isEqualTo(WarpNameRule.Verdict.FINE);
        }
    }

    @Nested
    @DisplayName("names that are not")
    class Refused {

        @Test
        @DisplayName("nothing at all")
        void blank() {
            assertThat(rule.check(null)).isEqualTo(WarpNameRule.Verdict.EMPTY);
            assertThat(rule.check("")).isEqualTo(WarpNameRule.Verdict.EMPTY);
            assertThat(rule.check("   ")).isEqualTo(WarpNameRule.Verdict.EMPTY);
        }

        @Test
        @DisplayName("one longer than the limit")
        void tooLong() {
            assertThat(rule.check("a".repeat(25))).isEqualTo(WarpNameRule.Verdict.TOO_LONG);
        }

        @Test
        @DisplayName("one with a space, which nobody could type at the command")
        void hasASpace() {
            assertThat(rule.check("the old quarry")).isEqualTo(WarpNameRule.Verdict.BAD_CHARACTERS);
        }

        @Test
        @DisplayName("one with markup in it")
        void hasMarkup() {
            // A warp name goes into a message, a lore line and a chat row. One carrying its own
            // tags is one that can paint or hide the text around it — the module's own wording, on
            // somebody else's screen.
            assertThat(rule.check("<red>spawn")).isEqualTo(WarpNameRule.Verdict.BAD_CHARACTERS);
            assertThat(rule.check("spawn&c")).isEqualTo(WarpNameRule.Verdict.BAD_CHARACTERS);
        }

        @Test
        @DisplayName("one the command would read as a subcommand")
        void isASubcommand() {
            // The whole reason this rule exists. /warp list has to mean one of the two, and it
            // means the subcommand — so a warp called list is one nothing can ever reach.
            for (String reserved : WarpNameRule.RESERVED) {
                assertThat(rule.check(reserved))
                        .as("a warp called %s could never be warped to", reserved)
                        .isEqualTo(WarpNameRule.Verdict.RESERVED);
            }
        }

        @Test
        @DisplayName("a subcommand however it was capitalised")
        void subcommandsAreCaseInsensitive() {
            // The command matches case-insensitively, so /warp LIST is still the subcommand and a
            // warp called LIST is still unreachable.
            assertThat(rule.check("LIST")).isEqualTo(WarpNameRule.Verdict.RESERVED);
            assertThat(rule.check("Admin")).isEqualTo(WarpNameRule.Verdict.RESERVED);
        }

        @Test
        @DisplayName("the reserved list is the command's own, so the two cannot drift")
        void theReservedListIsNotAGuess() {
            assertThat(WarpNameRule.RESERVED)
                    .as("if this is empty the rule above is checking nothing")
                    .isNotEmpty()
                    .contains("list", "admin", "set", "delete");
        }
    }

    @Nested
    @DisplayName("how many there may be")
    class HowMany {

        @Test
        @DisplayName("under the ceiling is fine")
        void underTheCeiling() {
            assertThat(rule.isRoomFor(199, 200)).isTrue();
        }

        @Test
        @DisplayName("at the ceiling is not")
        void atTheCeiling() {
            // Refused with a line saying so rather than quietly doing nothing, which is the version
            // of this that gets reported as "setting warps has stopped working".
            assertThat(rule.isRoomFor(200, 200)).isFalse();
            assertThat(rule.isRoomFor(201, 200)).isFalse();
        }
    }

    @Nested
    @DisplayName("saying why")
    class Wording {

        @Test
        @DisplayName("every verdict but the good one names a message")
        void everyRefusalCanBeSaid() {
            for (WarpNameRule.Verdict verdict : WarpNameRule.Verdict.values()) {
                if (verdict == WarpNameRule.Verdict.FINE) {
                    assertThat(verdict.messageKey())
                            .as("a name that is fine has nothing to say about it")
                            .isNull();
                    continue;
                }
                assertThat(verdict.messageKey())
                        .as("%s has no wording, so a refusal would be silent — and a silent "
                                + "refusal is a command people type four more times", verdict)
                        .startsWith("warps.");
            }
        }
    }
}
