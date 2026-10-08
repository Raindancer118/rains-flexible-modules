package de.raindancer.modules.moderation.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class StatisticsTest {

    @Test
    @DisplayName("Poisson: at least 0 of anything is certain; textbook values otherwise")
    void poisson() {
        assertThat(Statistics.poissonAtLeast(0, 3)).isEqualTo(1.0);
        // P(X >= 1 | 1) = 1 - e^-1
        assertThat(Statistics.poissonAtLeast(1, 1)).isCloseTo(1 - Math.exp(-1), within(1e-12));
        // P(X >= 3 | 0.5) = 1 - e^-0.5 (1 + 0.5 + 0.125)
        assertThat(Statistics.poissonAtLeast(3, 0.5)).isCloseTo(1 - Math.exp(-0.5) * 1.625, within(1e-12));
        assertThat(Statistics.poissonAtLeast(5, 0)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Poisson: far-out tails stay accurate instead of rounding to zero or going negative")
    void poissonTail() {
        double p = Statistics.poissonAtLeast(40, 2);
        assertThat(p).isPositive().isLessThan(1e-25);
        assertThat(Statistics.poissonAtLeast(2000, 1900)).isBetween(0.0, 0.05);
    }

    @Test
    @DisplayName("binomial: a fair coin landing heads 10 of 10 times is 1 in 1024")
    void binomial() {
        assertThat(Statistics.binomialAtLeast(10, 10, 0.5)).isCloseTo(1 / 1024.0, within(1e-12));
        assertThat(Statistics.binomialAtLeast(0, 10, 0.5)).isEqualTo(1.0);
        assertThat(Statistics.binomialAtLeast(5, 10, 0.5)).isCloseTo(0.623046875, within(1e-12));
    }

    @Test
    @DisplayName("Fisher: one p-value alone comes back unchanged; agreeing weak evidence adds up")
    void fisher() {
        assertThat(Statistics.fisher(new double[]{0.03})).isCloseTo(0.03, within(1e-12));
        double combined = Statistics.fisher(new double[]{0.01, 0.01, 0.01});
        assertThat(combined).isLessThan(0.001);
        assertThat(Statistics.fisher(new double[]{1, 1})).isCloseTo(1, within(1e-12));
        assertThat(Statistics.fisher(new double[]{})).isEqualTo(1.0);
    }

    @Test
    @DisplayName("score is -log10(p), capped so a perfect cheat does not print infinity")
    void score() {
        assertThat(Statistics.score(1e-6)).isCloseTo(6, within(1e-9));
        assertThat(Statistics.score(0)).isEqualTo(Statistics.MAX_SCORE);
        assertThat(Statistics.score(1)).isZero();
    }
}
