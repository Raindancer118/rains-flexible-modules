package de.raindancer.modules.enchants.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TyrantTest {

    @Test
    @DisplayName("a zombie tyrant calls for help once: the hit that takes it below half")
    void reinforcements() {
        assertThat(Tyrant.callsForHelp(120, 59, 120, false)).isTrue();
        assertThat(Tyrant.callsForHelp(120, 61, 120, false)).as("still above half").isFalse();
        assertThat(Tyrant.callsForHelp(50, 40, 120, false)).as("already below before").isFalse();
        assertThat(Tyrant.callsForHelp(120, 10, 120, true)).as("once only").isFalse();
    }

    @Test
    @DisplayName("a skeleton tyrant fires in bursts: an arrow every other tick for a while, then a pause")
    void minigun() {
        int shots = 0;
        for (int tick = 0; tick < Tyrant.BURST_TICKS; tick++) {
            shots += Tyrant.fires(tick) ? 1 : 0;
        }
        assertThat(shots).isEqualTo(24);
        for (int tick = Tyrant.BURST_TICKS; tick < Tyrant.BURST_TICKS + Tyrant.PAUSE_TICKS; tick++) {
            assertThat(Tyrant.fires(tick)).as("pausing at " + tick).isFalse();
        }
        assertThat(Tyrant.fires(Tyrant.BURST_TICKS + Tyrant.PAUSE_TICKS)).as("the next burst").isTrue();
    }

    @Test
    @DisplayName("a baby zombie tyrant runs 20 % faster than a tyrant already does; nobody else changes")
    void babyTyrant() {
        assertThat(ShardTier.TYRANT.speedFactor(true)).isCloseTo(ShardTier.TYRANT.speedFactor() * 1.2,
                org.assertj.core.api.Assertions.within(1e-9));
        assertThat(ShardTier.TYRANT.speedFactor(false)).isEqualTo(ShardTier.TYRANT.speedFactor());
        assertThat(ShardTier.CRYSTAL.speedFactor(true)).isEqualTo(ShardTier.CRYSTAL.speedFactor());
    }
}
