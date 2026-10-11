package de.raindancer.modules.anticheat.rules;

import de.raindancer.modules.anticheat.model.SlowState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SlowdownRuleTest {

    private final SlowdownRule rule = new SlowdownRule();

    /** A state that has been on for this many moves, the first of them a fresh press. */
    private static SlowState held(int moves) {
        SlowState state = new SlowState();
        for (int i = 0; i < moves; i++) {
            state.move(true, 1);
        }
        return state;
    }

    @Test
    @DisplayName("the first sneaking moves are not slowed yet: the client crouches at the end of the tick the key went down")
    void startOfSneak() {
        assertThat(rule.sneak(held(1), 0.3)).isEqualTo(1.0);
        assertThat(rule.sneak(held(2), 0.3)).isEqualTo(1.0);
        assertThat(rule.sneak(held(3), 0.3)).isEqualTo(0.3);
        assertThat(rule.sneak(held(40), 0.3)).isEqualTo(0.3);
    }

    @Test
    @DisplayName("not sneaking is never slowed, and Swift Sneak (attribute above the base) is read as it is")
    void notSneaking() {
        assertThat(rule.sneak(new SlowState(), 0.3)).isEqualTo(1.0);
        assertThat(rule.sneak(held(10), 0.75)).isCloseTo(0.75, within(1e-9));
        assertThat(rule.sneak(held(10), 2.0)).as("an attribute above 1 never speeds anybody up").isEqualTo(1.0);
        assertThat(rule.sneak(held(10), -1.0)).as("nor below 0").isEqualTo(0.0);
    }

    @Test
    @DisplayName("using an item slows from the move after the packet that started it was read")
    void usingAnItem() {
        assertThat(rule.use(new SlowState(), 0.2)).isEqualTo(1.0);
        assertThat(rule.use(held(1), 0.2)).isEqualTo(1.0);
        assertThat(rule.use(held(3), 0.2)).isEqualTo(0.2);
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
                rule.sneak(held(1), 0.3), false, Double.NaN, 0);
        assertThat(horizontal.judge(tapped).passed()).isTrue();
    }

    @Test
    @DisplayName("sprinting into a sneak: the first move keeps its momentum and the sprint's acceleration")
    void sprintToSneak() {
        HorizontalRule horizontal = new HorizontalRule();
        double sprinting = 0.2864;
        double firstSneakMove = sprinting * 0.546 + 0.13;
        HorizontalRule.Move move = new HorizontalRule.Move(sprinting, firstSneakMove, 1, true, true, 0.6, 0.6, 0.1, false, true,
                rule.sneak(held(1), 0.3), false, Double.NaN, 0);
        assertThat(horizontal.judge(move).passed()).isTrue();
        HorizontalRule.Move later = new HorizontalRule.Move(sprinting, firstSneakMove, 1, true, true, 0.6, 0.6, 0.1, false, true,
                rule.sneak(held(6), 0.3), false, Double.NaN, 0);
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
                    rule.sneak(held(Math.min(tick, 5)), 0.3), false, Double.NaN, 0);
            assertThat(horizontal.judge(move).passed()).as("tick %d", tick).isTrue();
            last = v;
        }
        HorizontalRule.Move walking = new HorizontalRule.Move(last, 0.1 + last * 0.546, 1, true, true, 0.6, 0.6, 0.1, false, true,
                rule.sneak(held(60), 0.3), false, Double.NaN, 0);
        assertThat(horizontal.judge(walking).outcome()).isEqualTo(HorizontalRule.Outcome.NO_SLOW);
    }

    /** What a client re-pressing the key every other tick earns: walking pace on every press tick. */
    private int flagsWhenTogglingEvery(int period, boolean sneaking) {
        HorizontalRule horizontal = new HorizontalRule();
        SlowState state = new SlowState();
        double v = 0;
        int flags = 0;
        for (int tick = 0; tick < 200; tick++) {
            boolean down = tick % period == 0;
            state.move(down, 1);
            double factor = sneaking ? rule.sneak(state, 0.3) : rule.use(state, 0.2);
            double last = v;
            // The cheat walks at full pace the whole time, flickering the key.
            v = v * 0.546 + 0.1;
            HorizontalRule.Move move = new HorizontalRule.Move(last, v, 1, true, true, 0.6, 0.6, 0.1, false, true, factor, false, Double.NaN, 0);
            if (horizontal.judge(move).outcome() == HorizontalRule.Outcome.NO_SLOW) {
                flags++;
            }
        }
        return flags;
    }

    @Test
    @DisplayName("re-pressing sneak every other tick, or raising a shield every other tick, still flags NoSlow: the grace is not renewable")
    void togglingFlags() {
        assertThat(flagsWhenTogglingEvery(2, true)).as("sneak toggled every 2 ticks").isGreaterThan(50);
        assertThat(flagsWhenTogglingEvery(2, false)).as("item raised every 2 ticks").isGreaterThan(50);
        assertThat(flagsWhenTogglingEvery(3, true)).as("every 3 ticks").isGreaterThan(30);
    }

    @Test
    @DisplayName("tapping shift now and then at walking pace on the press tick never flags")
    void honestTapsPass() {
        assertThat(flagsWhenTogglingEvery(15, true)).isZero();
    }
}
