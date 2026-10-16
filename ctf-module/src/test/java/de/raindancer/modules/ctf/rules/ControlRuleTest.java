package de.raindancer.modules.ctf.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("the control point")
class ControlRuleTest {

    private final ControlRule rule = new ControlRule();

    @Test
    @DisplayName("one side alone on the point takes it over the capture time")
    void takes() {
        ControlRule.State state = new ControlRule.State(-1, -1, 0);
        for (int second = 0; second < 9; second++) {
            state = rule.tick(state, new int[]{2, 0}, 10);
        }
        assertThat(state.owner()).isEqualTo(-1);
        state = rule.tick(state, new int[]{2, 0}, 10);
        assertThat(state.owner()).isEqualTo(0);
        assertThat(state.progress()).isEqualTo(100);
    }

    @Test
    @DisplayName("both sides on it: nothing moves")
    void contested() {
        ControlRule.State state = new ControlRule.State(-1, 0, 40);
        assertThat(rule.tick(state, new int[]{1, 1}, 10)).isEqualTo(state);
    }

    @Test
    @DisplayName("the other side first wears the owner's hold down, then builds its own")
    void flips() {
        ControlRule.State state = new ControlRule.State(0, 0, 100);
        state = rule.tick(state, new int[]{0, 1}, 2);
        assertThat(state.owner()).isEqualTo(0);
        assertThat(state.progress()).isEqualTo(50);
        state = rule.tick(state, new int[]{0, 1}, 2);
        assertThat(state.owner()).as("worn down: nobody's").isEqualTo(-1);
        assertThat(state.progress()).isZero();
        state = rule.tick(state, new int[]{0, 1}, 2);
        state = rule.tick(state, new int[]{0, 1}, 2);
        assertThat(state.owner()).isEqualTo(1);
    }

    @Test
    @DisplayName("nobody on it: a half-taken point drains back slowly, an owned one stays")
    void empty() {
        ControlRule.State half = new ControlRule.State(-1, 1, 50);
        assertThat(rule.tick(half, new int[]{0, 0}, 10).progress()).isLessThan(50);
        ControlRule.State owned = new ControlRule.State(1, 1, 100);
        assertThat(rule.tick(owned, new int[]{0, 0}, 10)).isEqualTo(owned);
    }
}
