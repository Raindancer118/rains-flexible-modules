package de.raindancer.modules.mannequin.rules;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShieldBlockRuleTest {

    private final ShieldBlockRule rule = new ShieldBlockRule();

    @Test
    void raisesWhenEverythingLinesUp() {
        assertThat(rule.shouldRaiseShield(true, true, false, true)).isTrue();
    }

    @Test
    void neverWithoutAShield() {
        assertThat(rule.shouldRaiseShield(false, true, false, true)).isFalse();
    }

    @Test
    void neverWhenBlockingIsSwitchedOff() {
        assertThat(rule.shouldRaiseShield(true, false, false, true)).isFalse();
    }

    @Test
    void neverWhenAlreadyBlocking() {
        assertThat(rule.shouldRaiseShield(true, true, true, true)).isFalse();
    }

    @Test
    void neverWithoutAnAttackerNearby() {
        assertThat(rule.shouldRaiseShield(true, true, false, false)).isFalse();
    }
}
