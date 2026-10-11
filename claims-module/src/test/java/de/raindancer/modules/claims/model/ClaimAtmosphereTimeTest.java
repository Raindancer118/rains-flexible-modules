package de.raindancer.modules.claims.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClaimAtmosphereTimeTest {

    @Test
    @DisplayName("ticks read as a clock: tick 0 is six in the morning, 18000 is midnight")
    void clock() {
        assertThat(ClaimAtmosphere.clock(0)).isEqualTo("06:00");
        assertThat(ClaimAtmosphere.clock(6000)).isEqualTo("12:00");
        assertThat(ClaimAtmosphere.clock(18000)).isEqualTo("00:00");
        assertThat(ClaimAtmosphere.clock(23999)).isEqualTo("05:59");
        assertThat(ClaimAtmosphere.clock(8500)).isEqualTo("14:30");
    }

    @Test
    @DisplayName("a typed clock time or tick count becomes ticks; nonsense is refused")
    void parse() {
        assertThat(ClaimAtmosphere.parseTime("14:30")).hasValue(8500);
        assertThat(ClaimAtmosphere.parseTime("6:00")).hasValue(0);
        assertThat(ClaimAtmosphere.parseTime("00:00")).hasValue(18000);
        assertThat(ClaimAtmosphere.parseTime("12345")).hasValue(12345);
        assertThat(ClaimAtmosphere.parseTime("24000")).isEmpty();
        assertThat(ClaimAtmosphere.parseTime("25:00")).isEmpty();
        assertThat(ClaimAtmosphere.parseTime("12:60")).isEmpty();
        assertThat(ClaimAtmosphere.parseTime("noon")).isEmpty();
        assertThat(ClaimAtmosphere.parseTime(null)).isEmpty();
    }

    @Test
    @DisplayName("stepping goes back and forth by any amount, round midnight, from the world's time when following it")
    void shift() {
        ClaimAtmosphere atmosphere = new ClaimAtmosphere();
        atmosphere.shiftTime(1, 4000);
        assertThat(atmosphere.effectiveTicks()).as("from the world's time").isEqualTo(4001);
        atmosphere.shiftTime(-1, 4000);
        assertThat(atmosphere.effectiveTicks()).isEqualTo(4000);
        atmosphere.shiftTime(-5000, 4000);
        assertThat(atmosphere.effectiveTicks()).as("back past tick 0").isEqualTo(23000);
        atmosphere.shiftTime(1000, 4000);
        assertThat(atmosphere.effectiveTicks()).as("forward past the end of the day").isEqualTo(0);
        assertThat(atmosphere.overridesTime()).isTrue();

        atmosphere.timePreset(ClaimAtmosphere.TimePreset.NOON);
        atmosphere.shiftTime(17, 4000);
        assertThat(atmosphere.effectiveTicks()).as("from the preset, not the world").isEqualTo(6017);
    }
}
