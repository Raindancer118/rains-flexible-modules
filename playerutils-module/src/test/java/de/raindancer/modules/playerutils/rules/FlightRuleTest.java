package de.raindancer.modules.playerutils.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** When granted flight has to be put back, because the game took it away on its own. */
class FlightRuleTest {

    private final FlightRule rule = new FlightRule();

    @Test
    @DisplayName("granted flight is put back in survival and adventure, where the game drops it")
    void putBack() {
        assertThat(rule.shouldRestore(true, "SURVIVAL", false)).isTrue();
        assertThat(rule.shouldRestore(true, "ADVENTURE", false)).isTrue();
    }

    @Test
    @DisplayName("not where the game flies by itself, not when they already can, not when never granted")
    void leftAlone() {
        assertThat(rule.shouldRestore(true, "CREATIVE", false)).isFalse();
        assertThat(rule.shouldRestore(true, "SPECTATOR", false)).isFalse();
        assertThat(rule.shouldRestore(true, "SURVIVAL", true)).isFalse();
        assertThat(rule.shouldRestore(false, "SURVIVAL", false)).isFalse();
    }

    @Test
    @DisplayName("toggle / on / off")
    void wanted() {
        assertThat(rule.wanted("toggle", true)).isFalse();
        assertThat(rule.wanted("toggle", false)).isTrue();
        assertThat(rule.wanted("on", true)).isTrue();
        assertThat(rule.wanted("off", false)).isFalse();
    }
}
