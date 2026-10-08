package de.raindancer.modules.anticheat.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PingRuleTest {

    private final PingRule rule = new PingRule();

    @Test
    @DisplayName("the median ignores one slow answer")
    void median() {
        assertThat(rule.median(new double[]{40, 42, 41, 900, 39})).isCloseTo(41, within(1e-9));
        assertThat(rule.median(new double[]{})).isNaN();
    }

    @Test
    @DisplayName("a keep-alive ping near the real round trip is honest; far above it is held back on purpose")
    void spoof() {
        double[] real = {40, 42, 41, 45, 39, 44, 40, 43};
        assertThat(rule.spoofed(60, real).passed()).isTrue();
        assertThat(rule.spoofed(400, real).failed()).isTrue();
        assertThat(rule.spoofed(400, new double[]{40, 41}).passed()).as("too few answers to say").isTrue();
    }

    @Test
    @DisplayName("a genuinely slow connection is slow both ways and not a spoof")
    void slowIsNotSpoof() {
        double[] slow = {380, 420, 395, 410, 405, 399, 388, 415};
        assertThat(rule.spoofed(430, slow).passed()).isTrue();
    }
}
