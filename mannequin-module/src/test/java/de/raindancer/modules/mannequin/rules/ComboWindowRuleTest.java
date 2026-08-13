package de.raindancer.modules.mannequin.rules;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ComboWindowRuleTest {

    private final ComboWindowRule rule = new ComboWindowRule();

    @Test
    void aFirstHitNeverContinuesAnything() {
        assertThat(rule.continuesCombo(0L, 1000L, 2000L)).isFalse();
        assertThat(rule.continuesCombo(-5L, 1000L, 2000L)).isFalse();
    }

    @Test
    void aHitInsideTheWindowContinues() {
        assertThat(rule.continuesCombo(1000L, 2500L, 2000L)).isTrue();
        assertThat(rule.continuesCombo(1000L, 3000L, 2000L)).isTrue();
    }

    @Test
    void aHitAfterTheWindowStartsFresh() {
        assertThat(rule.continuesCombo(1000L, 3001L, 2000L)).isFalse();
    }

    @Test
    void exactlyAtTheEdgeStillContinues() {
        assertThat(rule.continuesCombo(1000L, 1000L + 2000L, 2000L)).isTrue();
    }

    @Test
    void timeMovingBackwardsNeverContinues() {
        assertThat(rule.continuesCombo(5000L, 4000L, 2000L)).isFalse();
    }

    @Test
    void aNegativeWindowBehavesAsZero() {
        assertThat(rule.continuesCombo(1000L, 1000L, -50L)).isTrue();
        assertThat(rule.continuesCombo(1000L, 1001L, -50L)).isFalse();
    }
}
