package de.raindancer.modules.anticheat.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Anti-knockback that only cancels the sideways push: the client stays put while the hit should have
 * thrown it back. Vanilla halves the speed they had and adds the push; one tick of their own input can
 * work against it, no more.
 */
class HorizontalKnockbackTest {

    private final MotionRule rule = new MotionRule();

    @Test
    @DisplayName("standing still and thrown back the full push: taken")
    void taken() {
        assertThat(rule.knockbackTakenSideways(0.4, 0.38, 0).failed()).isFalse();
    }

    @Test
    @DisplayName("standing still and not moving an inch: anti-knockback")
    void cancelled() {
        Judgement judged = rule.knockbackTakenSideways(0.4, 0.0, 0);
        assertThat(judged.failed()).isTrue();
        assertThat(judged.reason()).contains("sideways");
    }

    @Test
    @DisplayName("running at the attacker and pushing against it: half the old speed and a tick of input are allowed")
    void runningInto() {
        // 0.4 back, minus half of 0.28 running in, minus a sprinting tick against it.
        assertThat(rule.knockbackTakenSideways(0.4, 0.4 - 0.14 - 0.13, 0.28).failed()).isFalse();
        assertThat(rule.knockbackTakenSideways(0.4, -0.05, 0.28).failed()).isTrue();
    }

    @Test
    @DisplayName("a push too small to tell from walking is not judged")
    void tooSmall() {
        assertThat(rule.knockbackTakenSideways(0.15, -0.2, 0.28).failed()).isFalse();
    }
}
