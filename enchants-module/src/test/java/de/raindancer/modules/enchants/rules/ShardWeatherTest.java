package de.raindancer.modules.enchants.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;

class ShardWeatherTest {

    @Test
    @DisplayName("a thunderstorm as often as the percent says, over many rolls")
    void asOftenAsSet() {
        SplittableRandom random = new SplittableRandom(14);
        int storms = 0;
        for (int i = 0; i < 10_000; i++) {
            storms += ShardWeather.thunder(random, 70) ? 1 : 0;
        }
        assertThat(storms).isBetween(6_800, 7_200);
    }

    @Test
    @DisplayName("0 is never a storm, 100 always")
    void bounds() {
        SplittableRandom random = new SplittableRandom(1);
        for (int i = 0; i < 1_000; i++) {
            assertThat(ShardWeather.thunder(random, 0)).isFalse();
            assertThat(ShardWeather.thunder(random, 100)).isTrue();
        }
    }
}
