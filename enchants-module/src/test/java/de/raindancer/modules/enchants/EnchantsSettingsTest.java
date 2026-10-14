package de.raindancer.modules.enchants;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Every default spelled out: a positional record with swapped ints compiles perfectly. */
class EnchantsSettingsTest {

    @Test
    @DisplayName("the defaults are the ones the docs promise")
    void defaults() {
        EnchantsSettings defaults = EnchantsSettings.DEFAULTS;

        assertThat(defaults.flightSecondsPerLevel()).isEqualTo(30);
        assertThat(defaults.flightRechargeSeconds()).isEqualTo(30);
        assertThat(defaults.landingGraceSeconds()).isEqualTo(8);
        assertThat(defaults.doubleJumpPowerPercent()).isEqualTo(100);
        assertThat(defaults.boostRechargeSeconds()).isEqualTo(90);
        assertThat(defaults.boostSpeedPercent()).isEqualTo(150);
        assertThat(defaults.launchSpeed()).isEqualTo(200);
        assertThat(defaults.launchCooldownSeconds()).isEqualTo(600);
        assertThat(defaults.wisdomPercentPerLevel()).isEqualTo(50);
    }
}
