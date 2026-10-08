package de.raindancer.modules.anticheat.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AirControlRuleTest {

    private final AirControlRule rule = new AirControlRule();

    @Test
    @DisplayName("gliding on with friction and a little steering is vanilla")
    void vanilla() {
        // 0.3 east, next tick 0.91 of it plus 0.026 sideways
        assertThat(rule.judge(0.3, 0, 0.273, 0.026, 1).passed()).isTrue();
    }

    @Test
    @DisplayName("turning a right angle in mid-air at full speed is strafe")
    void rightAngle() {
        Judgement judged = rule.judge(0.3, 0, 0, 0.3, 1);
        assertThat(judged.failed()).isTrue();
        assertThat(judged.reason()).contains("air");
    }

    @Test
    @DisplayName("stopping dead in mid-air is not possible either — unless a wall stopped them")
    void stopping() {
        assertThat(rule.judge(0.3, 0, 0, 0, 1).failed()).isTrue();
    }

    @Test
    @DisplayName("two ticks in one move get two ticks of steering")
    void twoTicks() {
        double first = 0.3 * 0.91 + 0.026;
        double second = first * 0.91 + 0.026;
        assertThat(rule.judge(0.3, 0, first + second, 0, 2).passed()).isTrue();
    }

    @Test
    @DisplayName("climbing a ladder at 0.2 is vanilla; faster is not")
    void ladder() {
        assertThat(rule.climb(0.2).passed()).isTrue();
        assertThat(rule.climb(0.45).failed()).isTrue();
    }
}
