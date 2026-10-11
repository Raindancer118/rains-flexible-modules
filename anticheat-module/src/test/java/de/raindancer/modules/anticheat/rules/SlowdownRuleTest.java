package de.raindancer.modules.anticheat.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SlowdownRuleTest {

    private final SlowdownRule rule = new SlowdownRule();

    @Test
    @DisplayName("the first sneaking moves are not slowed yet: the client crouches at the end of the tick the key went down")
    void startOfSneak() {
        assertThat(rule.sneak(1, 0.3)).isEqualTo(1.0);
        assertThat(rule.sneak(SlowdownRule.SETTLE, 0.3)).isEqualTo(1.0);
        assertThat(rule.sneak(SlowdownRule.SETTLE + 1, 0.3)).isEqualTo(0.3);
        assertThat(rule.sneak(40, 0.3)).isEqualTo(0.3);
    }

    @Test
    @DisplayName("not sneaking is never slowed, and Swift Sneak (attribute above the base) is read as it is")
    void notSneaking() {
        assertThat(rule.sneak(0, 0.3)).isEqualTo(1.0);
        assertThat(rule.sneak(10, 0.75)).isCloseTo(0.75, within(1e-9));
        assertThat(rule.sneak(10, 2.0)).as("an attribute above 1 never speeds anybody up").isEqualTo(1.0);
        assertThat(rule.sneak(10, -1.0)).as("nor below 0").isEqualTo(0.0);
    }

    @Test
    @DisplayName("using an item slows from the move after the packet that started it was read")
    void usingAnItem() {
        assertThat(rule.use(0, 0.2)).isEqualTo(1.0);
        assertThat(rule.use(1, 0.2)).isEqualTo(1.0);
        assertThat(rule.use(SlowdownRule.SETTLE + 1, 0.2)).isEqualTo(0.2);
    }

    @Test
    @DisplayName("tapping the sneak key walks at 0.119 on the tick it is up: not NoSlow")
    void sneakTapping() {
        // Lilly's SMP: "noslow: 0.119 while sneaking, at most 0.104" — one unslowed tick between two sneaking ones.
        HorizontalRule horizontal = new HorizontalRule();
        double carried = 0.0187;
        double hd = carried + 0.1;
        HorizontalRule.Move stale = new HorizontalRule.Move(carried / 0.546, hd, 1, true, true, 0.6, 0.6, 0.1, false, true,
                0.3, false, Double.NaN, 0);
        assertThat(horizontal.judge(stale).outcome()).as("the old judgement, sneaking assumed").isEqualTo(HorizontalRule.Outcome.NO_SLOW);
        HorizontalRule.Move tapped = new HorizontalRule.Move(carried / 0.546, hd, 1, true, true, 0.6, 0.6, 0.1, false, true,
                rule.sneak(1, 0.3), false, Double.NaN, 0);
        assertThat(horizontal.judge(tapped).passed()).isTrue();
    }

    @Test
    @DisplayName("sprinting into a sneak: the first move keeps its momentum and the sprint's acceleration")
    void sprintToSneak() {
        HorizontalRule horizontal = new HorizontalRule();
        double sprinting = 0.2864;
        double firstSneakMove = sprinting * 0.546 + 0.13;
        HorizontalRule.Move move = new HorizontalRule.Move(sprinting, firstSneakMove, 1, true, true, 0.6, 0.6, 0.1, false, true,
                rule.sneak(1, 0.3), false, Double.NaN, 0);
        assertThat(horizontal.judge(move).passed()).isTrue();
        HorizontalRule.Move later = new HorizontalRule.Move(sprinting, firstSneakMove, 1, true, true, 0.6, 0.6, 0.1, false, true,
                rule.sneak(6, 0.3), false, Double.NaN, 0);
        assertThat(horizontal.judge(later).outcome()).as("the same move six ticks into a sneak").isEqualTo(HorizontalRule.Outcome.NO_SLOW);
    }

    @Test
    @DisplayName("a steady sneak walk of 0.066 a tick passes for ever, and a sneak at walking pace does not")
    void steadySneak() {
        HorizontalRule horizontal = new HorizontalRule();
        double v = 0;
        double last = 0;
        for (int tick = 1; tick < 60; tick++) {
            v = v * 0.546 + 0.1 * 0.3;
            HorizontalRule.Move move = new HorizontalRule.Move(last, v, 1, true, true, 0.6, 0.6, 0.1, false, true,
                    rule.sneak(tick, 0.3), false, Double.NaN, 0);
            assertThat(horizontal.judge(move).passed()).as("tick %d", tick).isTrue();
            last = v;
        }
        HorizontalRule.Move walking = new HorizontalRule.Move(last, 0.1 + last * 0.546, 1, true, true, 0.6, 0.6, 0.1, false, true,
                rule.sneak(60, 0.3), false, Double.NaN, 0);
        assertThat(horizontal.judge(walking).outcome()).isEqualTo(HorizontalRule.Outcome.NO_SLOW);
    }
}
