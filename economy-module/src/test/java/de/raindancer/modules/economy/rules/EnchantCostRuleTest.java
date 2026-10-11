package de.raindancer.modules.economy.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class EnchantCostRuleTest {

    private final EnchantCostRule rule = new EnchantCostRule();

    @Test
    @DisplayName("experience points to reach a level, as vanilla counts them")
    void points() {
        assertThat(EnchantCostRule.points(0)).isZero();
        assertThat(EnchantCostRule.points(1)).isEqualTo(7);
        assertThat(EnchantCostRule.points(16)).isEqualTo(352);
        assertThat(EnchantCostRule.points(17)).isEqualTo(394);
        assertThat(EnchantCostRule.points(30)).isEqualTo(1395);
        assertThat(EnchantCostRule.points(32)).isEqualTo(1628);
    }

    @Test
    @DisplayName("a level-I enchantment costs a full enchanting-table roll: 3 lapis and levels 27 to 30")
    void roll() {
        assertThat(rule.roll(3.0, 40)).isEqualTo(3 * 40 + 306 * 3.0);
        assertThat(rule.roll(0, 40)).as("experience without a price: nothing to work it out from").isZero();
        assertThat(rule.roll(3.0, 0)).as("lapis without a price counts as free").isEqualTo(918.0);
    }

    @Test
    @DisplayName("each level above I is two of the level below, a book and the anvil's levels")
    void ordinaryLadder() {
        assertThat(rule.ladder(5, 1.0, 1038, 70, 3.0)).containsExactly(1038, 2194, 4539, 9268, 18771);
        assertThat(rule.ladder(1, 12.0, 1038, 70, 3.0)).as("Mending: twelve times an ordinary level").containsExactly(12456);
    }

    @Test
    @DisplayName("usefulness scales the whole ladder; a level is never cheaper than combining two of the level below")
    void usefulness() {
        long[] sharpness = rule.ladder(5, 1.8, 1038, 70, 3.0);
        assertThat(sharpness[0]).isEqualTo(1868);
        assertThat(sharpness[4]).isEqualTo(Math.round(1.8 * 18771));
        long[] bane = rule.ladder(5, 0.4, 1038, 70, 3.0);
        assertThat(bane[0]).isEqualTo(415);
        assertThat(bane[1]).as("held up by the anvil: two level-I books, a book's worth and two levels")
                .isEqualTo(2 * 415 + 70 + 48);
        assertThat(bane[4]).isLessThan(sharpness[4]);
    }

    @Test
    @DisplayName("for any usefulness, book and experience price: rising in level, and never under two lower books combined")
    void noArbitrage() {
        Random random = new Random(7);
        for (int i = 0; i < 5000; i++) {
            int max = 1 + random.nextInt(6);
            double factor = random.nextDouble() * 15;
            double point = random.nextDouble() * 50;
            long book = random.nextInt(5000);
            double roll = rule.roll(point, random.nextInt(500));
            long[] ladder = rule.ladder(max, factor, roll, book, point);
            for (int level = 2; level <= max; level++) {
                long lower = book + ladder[level - 2];
                long higher = book + ladder[level - 1];
                assertThat(higher).isGreaterThan(lower);
                assertThat(higher).isGreaterThanOrEqualTo(2 * lower + rule.anvil(level, point));
            }
        }
    }

    @Test
    @DisplayName("the shop never pays more for a level than buying two of the level below and combining them costs")
    void sellCaps() {
        long[] buy = {100, 1000, 5000};
        long[] caps = rule.sellCaps(buy, new boolean[] {true, true, true}, new long[] {0, 10, 20});
        assertThat(caps[0]).isEqualTo(Long.MAX_VALUE);
        assertThat(caps[1]).isEqualTo(2 * 100 + 10 - 1);
        assertThat(caps[2]).as("the cheapest way to level II is combining, not buying it")
                .isEqualTo(2 * (2 * 100 + 10) + 20 - 1);
        long[] closed = rule.sellCaps(buy, new boolean[] {false, true, true}, new long[] {0, 10, 20});
        assertThat(closed[1]).as("what the shop does not sell cannot be combined").isEqualTo(Long.MAX_VALUE);
        assertThat(closed[2]).isEqualTo(2 * 1000 + 20 - 1);
    }
}
