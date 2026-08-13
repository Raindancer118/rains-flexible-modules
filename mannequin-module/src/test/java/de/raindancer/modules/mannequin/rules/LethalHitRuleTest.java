package de.raindancer.modules.mannequin.rules;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LethalHitRuleTest {

    private final LethalHitRule rule = new LethalHitRule();

    @Test
    void twentyDamageWouldHaveKilled() {
        assertThat(rule.wouldHaveKilledUnarmoredPlayer(20.0)).isTrue();
    }

    @Test
    void moreThanTwentyWouldHaveKilled() {
        assertThat(rule.wouldHaveKilledUnarmoredPlayer(45.0)).isTrue();
    }

    @Test
    void lessThanTwentyWouldNotHaveKilled() {
        assertThat(rule.wouldHaveKilledUnarmoredPlayer(19.9)).isFalse();
        assertThat(rule.wouldHaveKilledUnarmoredPlayer(0.0)).isFalse();
    }
}
