package de.raindancer.modules.essentials;

import de.raindancer.modules.essentials.rules.SkyTokenRule;
import de.raindancer.modules.essentials.rules.SkyTokenRule.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether a clear-sky or a dawn token does anything — and when it would not, it is not used up: a token
 * spent on a sky that was already clear is a token stolen by a misclick.
 */
class SkyTokenRuleTest {

    private final SkyTokenRule rule = new SkyTokenRule();

    @Test
    @DisplayName("the sky token clears rain or thunder, and refuses a sky already clear or a world without one")
    void sky() {
        assertThat(rule.clearSky(true, true, false)).isEqualTo(Outcome.GO);
        assertThat(rule.clearSky(true, false, true)).isEqualTo(Outcome.GO);
        assertThat(rule.clearSky(true, false, false)).isEqualTo(Outcome.ALREADY_CLEAR);
        assertThat(rule.clearSky(false, true, false)).isEqualTo(Outcome.NO_SKY);
    }

    @Test
    @DisplayName("the dawn token works from sunset until dawn, and refuses the day or a world without a sky")
    void dawn() {
        assertThat(rule.skipNight(true, 13_000)).isEqualTo(Outcome.GO);
        assertThat(rule.skipNight(true, 12_000)).as("sunset").isEqualTo(Outcome.GO);
        assertThat(rule.skipNight(true, 23_500)).as("just before sunrise").isEqualTo(Outcome.GO);
        assertThat(rule.skipNight(true, 6_000)).isEqualTo(Outcome.ALREADY_DAY);
        assertThat(rule.skipNight(true, 0)).isEqualTo(Outcome.ALREADY_DAY);
        assertThat(rule.skipNight(false, 18_000)).isEqualTo(Outcome.NO_SKY);
    }

    @Test
    @DisplayName("morning is the next sunrise, keeping the day count moving forward")
    void morning() {
        assertThat(rule.nextMorning(13_000)).isEqualTo(24_000);
        assertThat(rule.nextMorning(5 * 24_000L + 18_000)).isEqualTo(6 * 24_000L);
    }
}
