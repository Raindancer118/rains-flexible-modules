package de.raindancer.modules.anticheat.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HorizontalRuleTest {

    private final HorizontalRule rule = new HorizontalRule();

    private static HorizontalRule.Move ground(double lastHd, double hd) {
        return new HorizontalRule.Move(lastHd, hd, 1, true, true, 0.6, 0.6, 0.1, false, true, 1, false, Double.NaN, 0);
    }

    @Test
    @DisplayName("sprinting from standstill to full speed on stone passes every tick")
    void sprintUp() {
        double v = 0;
        double last = 0;
        for (int tick = 0; tick < 40; tick++) {
            v = v * 0.546 + 0.13;
            assertThat(rule.judge(ground(last, v)).failed()).as("tick %d", tick).isFalse();
            last = v;
        }
    }

    @Test
    @DisplayName("double the sprint speed is speed")
    void doubleSpeed() {
        HorizontalRule.Result result = rule.judge(ground(0.2864, 0.57));
        assertThat(result.outcome()).isEqualTo(HorizontalRule.Outcome.SPEED);
        assertThat(result.offset()).isGreaterThan(0.2);
    }

    @Test
    @DisplayName("a sprint jump gets its boost; the same distance without jumping does not")
    void sprintJump() {
        double jumpTick = 0.2864 * 0.546 + 0.13 + 0.2;
        HorizontalRule.Move jumping = new HorizontalRule.Move(0.2864, jumpTick, 1, true, true, 0.6, 0.6, 0.1, false, true, 1, true, Double.NaN, 0);
        assertThat(rule.judge(jumping).failed()).isFalse();
        assertThat(rule.judge(ground(0.2864, jumpTick)).failed()).isTrue();
    }

    @Test
    @DisplayName("in the air momentum carries but barely accelerates; the tick after a jump still had ground friction")
    void airborne() {
        double jumpTick = 0.4864;
        double afterJump = jumpTick * 0.546 + 0.026;
        HorizontalRule.Move first = new HorizontalRule.Move(jumpTick, afterJump, 1, false, true, 0.6, 0.6, 0.1, false, true, 1, false, Double.NaN, 0);
        assertThat(rule.judge(first).failed()).isFalse();
        double gliding = afterJump * 0.91 + 0.026;
        HorizontalRule.Move air = new HorizontalRule.Move(afterJump, gliding, 1, false, false, 0.6, 0.6, 0.1, false, true, 1, false, Double.NaN, 0);
        assertThat(rule.judge(air).failed()).isFalse();
        HorizontalRule.Move faster = new HorizontalRule.Move(afterJump, gliding + 0.1, 1, false, false, 0.6, 0.6, 0.1, false, true, 1, false, Double.NaN, 0);
        assertThat(rule.judge(faster).failed()).isTrue();
    }

    @Test
    @DisplayName("ice lets momentum pile up — sprinting on ice to its own top speed passes")
    void ice() {
        double accel = Physics.groundAcceleration(0.13, 0.98);
        double v = 0;
        double last = 0;
        for (int tick = 0; tick < 200; tick++) {
            v = v * 0.98 * 0.91 + accel;
            HorizontalRule.Move move = new HorizontalRule.Move(last, v, 1, true, true, 0.98, 0.98, 0.1, false, true, 1, false, Double.NaN, 0);
            assertThat(rule.judge(move).failed()).as("tick %d", tick).isFalse();
            last = v;
        }
    }

    @Test
    @DisplayName("eating at full sprint speed is NoSlow, after the momentum has worn off")
    void noSlow() {
        HorizontalRule.Move eating = new HorizontalRule.Move(0.2864, 0.2864, 1, true, true, 0.6, 0.6, 0.1, false, false, 0.2, false, Double.NaN, 0);
        assertThat(rule.judge(eating).outcome()).isEqualTo(HorizontalRule.Outcome.NO_SLOW);
        double slowedV = 0.2864 * 0.546 + 0.1 * 0.2;
        HorizontalRule.Move slowing = new HorizontalRule.Move(0.2864, slowedV, 1, true, true, 0.6, 0.6, 0.1, false, false, 0.2, false, Double.NaN, 0);
        assertThat(rule.judge(slowing).failed()).isFalse();
    }

    @Test
    @DisplayName("speed effects come through the attribute")
    void speedEffect() {
        double attr = 0.1 * 1.4;
        double v = 0;
        double last = 0;
        for (int tick = 0; tick < 40; tick++) {
            v = v * 0.546 + attr * 1.3;
            HorizontalRule.Move move = new HorizontalRule.Move(last, v, 1, true, true, 0.6, 0.6, attr, false, true, 1, false, Double.NaN, 0);
            assertThat(rule.judge(move).failed()).isFalse();
            last = v;
        }
        assertThat(rule.judge(ground(last, v)).failed()).as("the same speed without the effect").isTrue();
    }

    @Test
    @DisplayName("knockback carries them as fast as it pushed")
    void knockback() {
        HorizontalRule.Move pushed = new HorizontalRule.Move(0, 0.8, 1, true, true, 0.6, 0.6, 0.1, false, true, 1, false, 0.7, 0);
        assertThat(rule.judge(pushed).failed()).isFalse();
    }

    @Test
    @DisplayName("two ticks in one move get two ticks' worth")
    void twoTicks() {
        double first = 0.2864 * 0.546 + 0.13;
        double second = first * 0.546 + 0.13;
        HorizontalRule.Move both = new HorizontalRule.Move(0.2864, first + second, 2, true, true, 0.6, 0.6, 0.1, false, true, 1, false, Double.NaN, 0);
        assertThat(rule.judge(both).failed()).isFalse();
    }
}
