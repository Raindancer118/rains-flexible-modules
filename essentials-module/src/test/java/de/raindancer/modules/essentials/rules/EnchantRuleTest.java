package de.raindancer.modules.essentials.rules;

import de.raindancer.core.platform.rule.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EnchantRuleTest {

    private final EnchantRule rule = new EnchantRule();

    /** A sword, sharpness (vanilla max 5), the viewer enchanting their own, holding no extra rights. */
    private static EnchantRule.Request.Builder sharpness(int level) {
        return EnchantRule.Request.builder().level(level).vanillaMax(5).fitsItem(true).holding(true).self(true);
    }

    @Nested
    @DisplayName("what is held")
    class Held {

        @Test
        @DisplayName("empty hands are refused with their own sentence")
        void emptyHands() {
            assertThat(rule.judge(sharpness(3).holding(false).build()).reason())
                    .isEqualTo("essentials.enchant.nothing-held");
        }
    }

    @Nested
    @DisplayName("who it is for")
    class Whom {

        @Test
        @DisplayName("somebody else needs the others node")
        void othersNeedTheirNode() {
            assertThat(rule.judge(sharpness(3).self(false).build()).reason())
                    .isEqualTo("essentials.enchant.not-others");
            assertThat(rule.judge(sharpness(3).self(false).mayOthers(true).build()).isAllowed()).isTrue();
        }
    }

    @Nested
    @DisplayName("the level")
    class Level {

        @Test
        @DisplayName("1 to 255 is the range, and nothing outside it is allowed even with every node")
        void range() {
            EnchantRule.Request.Builder everything = sharpness(0).mayBeyondMax(true).mayAnyItem(true).present(true);
            assertThat(rule.judge(everything.level(255).build()).isAllowed()).isTrue();
            assertThat(rule.judge(everything.level(256).build()).reason()).isEqualTo("essentials.enchant.level-range");
            assertThat(rule.judge(everything.level(-1).build()).reason()).isEqualTo("essentials.enchant.level-range");
        }

        @Test
        @DisplayName("over the vanilla maximum needs beyond-max, and the refusal says what the maximum is")
        void beyondMax() {
            Verdict verdict = rule.judge(sharpness(6).build());
            assertThat(verdict.reason()).isEqualTo("essentials.enchant.too-high");
            assertThat(verdict.detail()).isEqualTo("5");
            assertThat(rule.judge(sharpness(5).build()).isAllowed()).isTrue();
            assertThat(rule.judge(sharpness(200).mayBeyondMax(true).build()).isAllowed()).isTrue();
        }

        @Test
        @DisplayName("the ceiling a chooser may offer follows the node")
        void ceiling() {
            assertThat(EnchantRule.ceiling(5, false)).isEqualTo(5);
            assertThat(EnchantRule.ceiling(5, true)).isEqualTo(255);
            assertThat(EnchantRule.ceiling(0, false)).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("the item")
    class Item {

        @Test
        @DisplayName("an enchantment that does not fit needs any-item")
        void wrongItem() {
            assertThat(rule.judge(sharpness(1).fitsItem(false).build()).reason())
                    .isEqualTo("essentials.enchant.wrong-item");
            assertThat(rule.judge(sharpness(1).fitsItem(false).mayAnyItem(true).build()).isAllowed()).isTrue();
        }

        @Test
        @DisplayName("any-item does not lift the level limit, and beyond-max does not lift the item one")
        void independent() {
            assertThat(rule.judge(sharpness(9).fitsItem(false).mayAnyItem(true).build()).reason())
                    .isEqualTo("essentials.enchant.too-high");
            assertThat(rule.judge(sharpness(9).fitsItem(false).mayBeyondMax(true).build()).reason())
                    .isEqualTo("essentials.enchant.wrong-item");
        }
    }

    @Nested
    @DisplayName("removing")
    class Removing {

        @Test
        @DisplayName("level 0 removes what is there, whatever the item or the level limits")
        void removesWhatIsThere() {
            assertThat(rule.judge(sharpness(0).fitsItem(false).present(true).build()).isAllowed()).isTrue();
        }

        @Test
        @DisplayName("removing what is not there says so rather than pretending")
        void nothingToRemove() {
            assertThat(rule.judge(sharpness(0).present(false).build()).reason())
                    .isEqualTo("essentials.enchant.not-on-item");
        }
    }

    @Nested
    @DisplayName("typed levels")
    class Typed {

        @Test
        @DisplayName("numbers and the words for removal are understood")
        void parses() {
            assertThat(EnchantRule.parseLevel("5")).contains(5);
            assertThat(EnchantRule.parseLevel("255")).contains(255);
            assertThat(EnchantRule.parseLevel("0")).contains(0);
            assertThat(EnchantRule.parseLevel("Remove")).contains(0);
            assertThat(EnchantRule.parseLevel("off")).contains(0);
            assertThat(EnchantRule.parseLevel("99999999999")).contains(Integer.MAX_VALUE);
            assertThat(EnchantRule.parseLevel("-3")).contains(-3);
        }

        @Test
        @DisplayName("anything else is not a level, so a player name can follow")
        void notALevel() {
            assertThat(EnchantRule.parseLevel("Steve")).isEmpty();
            assertThat(EnchantRule.parseLevel("")).isEmpty();
            assertThat(EnchantRule.parseLevel(null)).isEmpty();
        }
    }
}
