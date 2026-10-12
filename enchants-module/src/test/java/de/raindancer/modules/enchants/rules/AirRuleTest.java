package de.raindancer.modules.enchants.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AirRuleTest {

    private final AirRule rule = new AirRule();

    @Test
    @DisplayName("flight time grows with the level, by the configured seconds")
    void capacity() {
        assertThat(rule.capacityMillis(0, 30)).isZero();
        assertThat(rule.capacityMillis(1, 30)).isEqualTo(30_000);
        assertThat(rule.capacityMillis(3, 30)).isEqualTo(90_000);
        assertThat(rule.capacityMillis(5, 45)).isEqualTo(225_000);
    }

    @Test
    @DisplayName("a double jump level is that many extra jumps, and they run out")
    void jumps() {
        assertThat(rule.jumpsLeft(2, 0)).isEqualTo(2);
        assertThat(rule.jumpsLeft(2, 1)).isEqualTo(1);
        assertThat(rule.jumpsLeft(2, 2)).isZero();
        assertThat(rule.jumpsLeft(2, 9)).isZero();
        assertThat(rule.jumpsLeft(0, 0)).isZero();
    }

    @Test
    @DisplayName("flight is allowed while there is time; a jump is allowed while there are jumps")
    void allowFlight() {
        assertThat(rule.mayFly(1, 1)).isTrue();
        assertThat(rule.mayFly(1, 0)).as("empty tank").isFalse();
        assertThat(rule.mayFly(0, 5_000)).as("no enchantment").isFalse();

        assertThat(rule.allowFlight(0, 0, 1, 0)).isTrue();
        assertThat(rule.allowFlight(0, 0, 1, 1)).as("the one jump is used").isFalse();
        assertThat(rule.allowFlight(1, 100, 0, 0)).isTrue();
        assertThat(rule.allowFlight(1, 0, 1, 1)).isFalse();
        assertThat(rule.allowFlight(1, 0, 1, 0)).as("an empty tank still leaves the jump").isTrue();
    }

    @Test
    @DisplayName("the jump is a push up, scaled by the power setting")
    void jumpPush() {
        assertThat(rule.jumpUp(100)).isEqualTo(0.75);
        assertThat(rule.jumpUp(200)).isEqualTo(1.5);
        assertThat(rule.jumpUp(10)).isEqualTo(0.075);
    }
}
