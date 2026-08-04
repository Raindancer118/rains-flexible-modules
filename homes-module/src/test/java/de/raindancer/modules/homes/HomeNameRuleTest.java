package de.raindancer.modules.homes;

import de.raindancer.modules.homes.rules.HomeNameRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a home may be called.
 *
 * <h2>Why the old rule is kept exactly</h2>
 * Because it is already on disk. Every home anybody has set went through {@code HomeNames.normalise}
 * — lower-cased, trimmed, {@code [a-z0-9_-]}, at most sixteen characters — and the name is the
 * <em>key</em> in {@code homes.yml}. A port that tightened the rule would refuse to load names people
 * already have; one that loosened it would let two homes normalise onto one key. So this is the same
 * rule, tested rather than assumed.
 */
class HomeNameRuleTest {

    private final HomeNameRule rule = new HomeNameRule();

    @Nested
    @DisplayName("normalising")
    class Normalising {

        @Test
        @DisplayName("an ordinary name is itself")
        void ordinary() {
            assertThat(rule.normalise("base")).isEqualTo("base");
            assertThat(rule.normalise("the_mine-2")).isEqualTo("the_mine-2");
        }

        @Test
        @DisplayName("capitals are folded, so Base and base are one home")
        void capitalsAreFolded() {
            // The name is the key on disk. Two spellings that are one home to a player have to be
            // one key, or /home Base and /home base are different places.
            assertThat(rule.normalise("Base")).isEqualTo("base");
            assertThat(rule.normalise("BASE")).isEqualTo("base");
        }

        @Test
        @DisplayName("surrounding space is dropped")
        void spaceIsTrimmed() {
            assertThat(rule.normalise("  base  ")).isEqualTo("base");
        }

        @Test
        @DisplayName("anything not allowed makes it no name at all")
        void rubbishIsRefused() {
            // Refused rather than stripped: silently turning "my base!" into "mybase" gives somebody
            // a home under a name they did not type and cannot guess.
            assertThat(rule.normalise("my base")).isNull();
            assertThat(rule.normalise("base!")).isNull();
            assertThat(rule.normalise("<red>base")).isNull();
            assertThat(rule.normalise("bäse")).isNull();
        }

        @Test
        @DisplayName("nothing is not a name")
        void blankIsRefused() {
            assertThat(rule.normalise(null)).isNull();
            assertThat(rule.normalise("")).isNull();
            assertThat(rule.normalise("   ")).isNull();
        }

        @Test
        @DisplayName("sixteen characters is allowed and seventeen is not")
        void theLengthLimitIsExact() {
            // The old limit, to the character. One more would refuse a name somebody already has.
            assertThat(rule.normalise("a".repeat(16))).isEqualTo("a".repeat(16));
            assertThat(rule.normalise("a".repeat(17))).isNull();
        }

        @Test
        @DisplayName("the limit is measured after trimming")
        void lengthIsMeasuredAfterTrimming() {
            assertThat(rule.normalise("  " + "a".repeat(16) + "  ")).isEqualTo("a".repeat(16));
        }
    }

    @Nested
    @DisplayName("the default name")
    class TheDefault {

        @Test
        @DisplayName("no name at all means the one called home")
        void nothingMeansHome() {
            // /sethome with no argument, which is what nearly everybody types.
            assertThat(rule.orDefault(null)).isEqualTo(HomeNameRule.DEFAULT_NAME);
            assertThat(rule.orDefault("")).isEqualTo(HomeNameRule.DEFAULT_NAME);
            assertThat(rule.orDefault("  ")).isEqualTo(HomeNameRule.DEFAULT_NAME);
        }

        @Test
        @DisplayName("the default is itself a name the rule allows")
        void theDefaultIsValid() {
            // Otherwise bare /sethome refuses itself, which is the one call nobody would test.
            assertThat(rule.normalise(HomeNameRule.DEFAULT_NAME))
                    .isEqualTo(HomeNameRule.DEFAULT_NAME);
        }

        @Test
        @DisplayName("a name that is given is used")
        void aGivenNameIsKept() {
            assertThat(rule.orDefault("Mine")).isEqualTo("mine");
        }

        @Test
        @DisplayName("a name that is given and invalid stays invalid rather than becoming the default")
        void anInvalidNameIsNotQuietlyReplaced() {
            // Silently turning "my base" into "home" would overwrite the home they already have
            // there, which is the worst possible reading of a typo.
            assertThat(rule.orDefault("my base")).isNull();
        }
    }

    @Nested
    @DisplayName("saying why")
    class Wording {

        @Test
        @DisplayName("the rule can be stated to somebody who broke it")
        void itExplainsItself() {
            assertThat(rule.describe())
                    .as("'that name will not do' is the answer people try four more spellings against")
                    .isNotBlank()
                    .contains("16");
        }
    }
}
