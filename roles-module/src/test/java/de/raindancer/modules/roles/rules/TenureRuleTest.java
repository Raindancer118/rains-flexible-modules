package de.raindancer.modules.roles.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class TenureRuleTest {

    private final TenureRule rule = new TenureRule();
    private static final long DAY = Duration.ofDays(1).toMillis();

    @Test
    @DisplayName("a new role starts at its starting share and grows evenly to full strength")
    void grows() {
        assertThat(rule.strength(0, 0, 40, 14)).isEqualTo(0.4);
        assertThat(rule.strength(0, 7 * DAY, 40, 14)).isEqualTo(0.7);
        assertThat(rule.strength(0, 14 * DAY, 40, 14)).isEqualTo(1.0);
        assertThat(rule.strength(0, 100 * DAY, 40, 14)).as("never past full").isEqualTo(1.0);
    }

    @Test
    @DisplayName("odd settings and clocks: no growth time is full at once, a clock set back is the start")
    void edges() {
        assertThat(rule.strength(0, 0, 40, 0)).isEqualTo(1.0);
        assertThat(rule.strength(10 * DAY, 0, 40, 14)).isEqualTo(0.4);
        assertThat(rule.strength(0, 0, 150, 14)).isEqualTo(1.0);
        assertThat(rule.strength(0, 0, -5, 14)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("a perk at some strength: rounded, never zero while it does anything, never past the perk")
    void scaled() {
        assertThat(rule.scaled(-15, 0.4)).isEqualTo(-6);
        assertThat(rule.scaled(-15, 1.0)).isEqualTo(-15);
        assertThat(rule.scaled(10, 0.7)).isEqualTo(7);
        assertThat(rule.scaled(-2, 0.1)).isEqualTo(-1);
        assertThat(rule.scaled(-15, 0.0)).isZero();
        assertThat(rule.scaled(0, 1.0)).isZero();
    }

    @Test
    @DisplayName("how long until full strength, for the role menu")
    void untilFull() {
        assertThat(rule.untilFull(0, 4 * DAY, 14)).isEqualTo(Duration.ofDays(10));
        assertThat(rule.untilFull(0, 20 * DAY, 14)).isZero();
    }
}
