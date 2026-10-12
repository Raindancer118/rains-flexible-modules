package de.raindancer.modules.anticheat.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A clicker that randomises its delay within bounds — 60 to 85 ms, say — passes the old "all the same"
 * test, but its clicks have no pauses, no outliers, and a spread flatter than a hand's.
 */
class ClickPatternTest {

    private final ClickRule rule = new ClickRule();

    private static double[] uniform(Random random, int n, double from, double to) {
        double[] intervals = new double[n];
        for (int i = 0; i < n; i++) {
            intervals[i] = from + random.nextDouble() * (to - from);
        }
        return intervals;
    }

    /** A hand: a centre with a long tail, and every so often it stops to aim or move. */
    private static double[] hand(Random random, int n, double centre) {
        double[] intervals = new double[n];
        for (int i = 0; i < n; i++) {
            double gap = centre + Math.abs(random.nextGaussian()) * 18 - 6;
            if (random.nextInt(25) == 0) {
                gap += 150 + random.nextInt(250);
            }
            intervals[i] = Math.max(25, gap);
        }
        return intervals;
    }

    @Test
    @DisplayName("a bounded random clicker at 14 cps is a pattern no hand makes")
    void randomClicker() {
        Judgement judged = rule.tooRegular(uniform(new Random(1), 100, 60, 85));
        assertThat(judged.failed()).isTrue();
        assertThat(judged.reason()).contains("cps").contains("no pause");
    }

    @Test
    @DisplayName("a hand at the same speed pauses and wanders, and passes")
    void fastHand() {
        for (int seed = 0; seed < 50; seed++) {
            assertThat(rule.tooRegular(hand(new Random(seed), 100, 66)).failed()).as("seed %d", seed).isFalse();
        }
    }

    @Test
    @DisplayName("slower than thirteen clicks a second is never judged, however even")
    void slow() {
        assertThat(rule.tooRegular(uniform(new Random(2), 100, 80, 95)).failed()).isFalse();
    }

    @Test
    @DisplayName("fewer than a hundred clicks are too few to say")
    void tooFew() {
        assertThat(rule.tooRegular(uniform(new Random(3), 60, 60, 85)).failed()).isFalse();
    }
}
