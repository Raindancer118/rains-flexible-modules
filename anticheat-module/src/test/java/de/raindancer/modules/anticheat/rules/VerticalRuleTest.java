package de.raindancer.modules.anticheat.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VerticalRuleTest {

    private static final double G = Physics.GRAVITY;
    private final VerticalRule rule = new VerticalRule();

    private static VerticalRule.Move air(double lastDy, double dy) {
        return new VerticalRule.Move(lastDy, dy, 1, false, false, false, G, 0.42, 0.6, Double.NaN, 0);
    }

    private static VerticalRule.Move fromGround(double lastDy, double dy, boolean landsOnGround) {
        return new VerticalRule.Move(lastDy, dy, 1, true, landsOnGround, false, G, 0.42, 0.6, Double.NaN, 0);
    }

    @Test
    @DisplayName("a whole vanilla jump passes, tick by tick, up and down again")
    void wholeJump() {
        double last = 0;
        double v = 0.42;
        assertThat(rule.judge(fromGround(0, v, false)).passed()).isTrue();
        last = v;
        for (int tick = 0; tick < 25; tick++) {
            double next = Physics.nextVertical(last, G);
            assertThat(rule.judge(air(last, next)).passed()).as("tick %d", tick).isTrue();
            last = next;
        }
    }

    @Test
    @DisplayName("hovering mid-air fails, and says so")
    void hover() {
        Judgement judged = rule.judge(air(0, 0));
        assertThat(judged.failed()).isTrue();
        assertThat(judged.reason()).startsWith("hovering");
    }

    @Test
    @DisplayName("rising mid-air without a jump fails")
    void rising() {
        assertThat(rule.judge(air(-0.0784, 0.1)).failed()).isTrue();
    }

    @Test
    @DisplayName("a jump higher than jump strength allows fails; jump boost makes it legal")
    void highJump() {
        assertThat(rule.judge(fromGround(0, 0.6, false)).failed()).isTrue();
        VerticalRule.Move boosted = new VerticalRule.Move(0, 0.6, 1, true, false, false, G,
                Physics.jumpVelocity(0.42, 2, 1), 0.6, Double.NaN, 0);
        assertThat(rule.judge(boosted).passed()).isTrue();
    }

    @Test
    @DisplayName("stepping up half a slab passes; a whole block in one tick fails")
    void step() {
        assertThat(rule.judge(fromGround(0, 0.5, true)).passed()).isTrue();
        Judgement full = rule.judge(fromGround(0, 1.0, true));
        assertThat(full.failed()).isTrue();
        assertThat(full.reason()).startsWith("stepped too high");
    }

    @Test
    @DisplayName("landing clips the fall, and that is fine")
    void landing() {
        assertThat(rule.judge(new VerticalRule.Move(-0.5, -0.1, 1, false, true, false, G, 0.42, 0.6, Double.NaN, 0)).passed()).isTrue();
        assertThat(rule.judge(new VerticalRule.Move(0, 0, 1, true, true, false, G, 0.42, 0.6, Double.NaN, 0)).passed()).isTrue();
    }

    @Test
    @DisplayName("knockback the server sent lets them rise, once")
    void knockback() {
        VerticalRule.Move pushed = new VerticalRule.Move(0, 0.4, 1, false, false, false, G, 0.42, 0.6, 0.4, 0);
        assertThat(rule.judge(pushed).passed()).isTrue();
    }

    @Test
    @DisplayName("slow falling: a gentle descent is fine")
    void slowFalling() {
        VerticalRule.Move slow = new VerticalRule.Move(-0.1, Physics.nextVertical(-0.1, Physics.SLOW_FALLING_GRAVITY),
                1, false, false, false, Physics.SLOW_FALLING_GRAVITY, 0.42, 0.6, Double.NaN, 0);
        assertThat(rule.judge(slow).passed()).isTrue();
    }

    @Test
    @DisplayName("falling faster than gravity fails; snapping down to the ground below a ledge too")
    void fastFall() {
        assertThat(rule.judge(air(-0.0784, -1.0)).failed()).isTrue();
        Judgement reverseStep = rule.judge(new VerticalRule.Move(0, -1.0, 1, true, true, false, G, 0.42, 0.6, Double.NaN, 0));
        assertThat(reverseStep.failed()).isTrue();
        assertThat(reverseStep.reason()).startsWith("falling faster");
    }

    @Test
    @DisplayName("hitting the head stops the rise, so the next tick may fall sooner than predicted")
    void ceiling() {
        VerticalRule.Move bumped = new VerticalRule.Move(0.1, -0.0784, 1, false, false, true, G, 0.42, 0.6, Double.NaN, 0);
        assertThat(rule.judge(bumped).passed()).isTrue();
    }

    @Test
    @DisplayName("a move that covers two ticks is judged against two ticks of gravity")
    void twoTicks() {
        double first = Physics.nextVertical(0.42, G);
        double second = Physics.nextVertical(first, G);
        VerticalRule.Move both = new VerticalRule.Move(0.42, first + second, 2, false, false, false, G, 0.42, 0.6, Double.NaN, 0);
        assertThat(rule.judge(both).passed()).isTrue();
        VerticalRule.Move hovering = new VerticalRule.Move(0.42, first, 2, false, false, false, G, 0.42, 0.6, Double.NaN, 0);
        assertThat(rule.judge(hovering).failed()).isTrue();
    }

    @Test
    @DisplayName("a slime block gives a fall back")
    void bounce() {
        VerticalRule.Move bounced = new VerticalRule.Move(-0.6, 0.55, 1, true, false, false, G, 0.42, 0.6, Double.NaN, 0.6);
        assertThat(rule.judge(bounced).passed()).isTrue();
    }

    @Test
    @DisplayName("nothing to compare against yet is no evidence")
    void unknown() {
        assertThat(rule.judge(air(Double.NaN, 5)).passed()).isTrue();
    }
}
