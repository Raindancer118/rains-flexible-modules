package de.raindancer.modules.mannequin.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HealthPresetsTest {

    @Test
    void aKnownPresetIsFound() {
        assertThat(HealthPresets.get("iron_golem")).contains(100.0);
    }

    @Test
    void lookupIsCaseInsensitive() {
        assertThat(HealthPresets.get("Iron_Golem")).contains(100.0);
        assertThat(HealthPresets.get("WITHER")).contains(300.0);
    }

    @Test
    void anUnknownNameIsEmpty() {
        assertThat(HealthPresets.get("not-a-real-mob")).isEmpty();
        assertThat(HealthPresets.get(null)).isEmpty();
    }

    @Test
    void everyPresetIsPositive() {
        assertThat(HealthPresets.all()).isNotEmpty();
        HealthPresets.all().values().forEach(health -> assertThat(health).isPositive());
    }
}
