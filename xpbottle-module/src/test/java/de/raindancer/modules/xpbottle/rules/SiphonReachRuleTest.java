package de.raindancer.modules.xpbottle.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** That the reach in the settings is the reach in blocks, and not its square root. */
class SiphonReachRuleTest {

    private final SiphonReachRule rule = new SiphonReachRule();

    @Test
    @DisplayName("an orb exactly at the edge of the reach is taken")
    void theEdgeCounts() {
        assertThat(rule.reaches(16.0, 4.0)).isTrue();      // 4 blocks away, 4 blocks of reach
    }

    @Test
    @DisplayName("an orb past the reach is left alone")
    void beyondTheEdgeIsLeft() {
        assertThat(rule.reaches(16.01, 4.0)).isFalse();
        assertThat(rule.reaches(100.0, 4.0)).isFalse();
    }

    @Test
    @DisplayName("the setting is read as blocks, so eight blocks of reach takes an orb six away")
    void theSettingIsInBlocks() {
        assertThat(rule.reaches(36.0, 8.0)).isTrue();
    }

    @Test
    @DisplayName("no reach reaches nothing, however close the orb is")
    void noReachTakesNothing() {
        assertThat(rule.reaches(0.0, 0.0)).isFalse();
        assertThat(rule.reaches(0.0, -1.0)).isFalse();
    }

    @Test
    @DisplayName("a distance that is not a number is not in reach")
    void nonsenseIsOutOfReach() {
        assertThat(rule.reaches(Double.NaN, 8.0)).isFalse();
        assertThat(rule.reaches(-1.0, 8.0)).isFalse();
    }
}
