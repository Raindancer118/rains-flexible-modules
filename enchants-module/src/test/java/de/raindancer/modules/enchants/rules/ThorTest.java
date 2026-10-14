package de.raindancer.modules.enchants.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;

class ThorTest {

    @Test
    @DisplayName("each level hits harder, throws again sooner and calls a bigger storm")
    void levels() {
        for (int level = 1; level < 3; level++) {
            assertThat(Thor.hammerDamage(level)).isLessThan(Thor.hammerDamage(level + 1));
            assertThat(Thor.cooldownTicks(level)).isGreaterThan(Thor.cooldownTicks(level + 1));
            assertThat(Thor.stormBolts(level)).isLessThan(Thor.stormBolts(level + 1));
            assertThat(Thor.stormRadius(level)).isLessThan(Thor.stormRadius(level + 1));
        }
        assertThat(Thor.cooldownTicks(3)).isPositive();
    }

    @Test
    @DisplayName("the storm's first bolt strikes where the trident is, the rest around it within the radius")
    void boltSpots() {
        for (int level = 1; level <= 3; level++) {
            List<double[]> spots = Thor.boltSpots(new SplittableRandom(level), level);
            assertThat(spots).hasSize(Thor.stormBolts(level));
            assertThat(spots.getFirst()).containsExactly(0.0, 0.0);
            for (double[] spot : spots) {
                assertThat(Math.hypot(spot[0], spot[1])).isLessThanOrEqualTo(Thor.stormRadius(level));
            }
        }
    }

    @Test
    @DisplayName("endermen are spared the rain in the shard world, never real water, and nowhere else")
    void endermen() {
        assertThat(Thor.sparesEnderman(true, false)).isTrue();
        assertThat(Thor.sparesEnderman(true, true)).as("standing in water").isFalse();
        assertThat(Thor.sparesEnderman(false, false)).as("another world").isFalse();
    }
}
