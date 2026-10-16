package de.raindancer.modules.ctf.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("a thrown flag's flight")
class FlagFlightTest {

    /** A flat floor whose top is y = 65. */
    private static boolean floor(double x, double y, double z) {
        return y < 65;
    }

    @Test
    @DisplayName("thrown forward it flies some blocks, then comes down on the floor")
    void arc() {
        double[] at = {0, 66.6, 0};
        double[] speed = {1.25, 0.2, 0};
        int ticks = 0;
        while (!FlagService.step(at, speed, FlagFlightTest::floor).landed() && ticks < FlagService.MOST_TICKS) {
            ticks++;
        }
        assertThat(ticks).isLessThan(FlagService.MOST_TICKS);
        assertThat(at[1]).as("on top of the floor").isEqualTo(65);
        assertThat(at[0]).as("a good throw goes a fair way").isBetween(6.0, 25.0);
    }

    @Test
    @DisplayName("a wall stops it going on, not falling")
    void wall() {
        double[] at = {0, 66.6, 0};
        double[] speed = {1.25, 0.2, 0};
        FlagService.Solid wallAtThree = (x, y, z) -> y < 65 || x >= 3;
        while (!FlagService.step(at, speed, wallAtThree).landed()) {
            assertThat(at[0]).isLessThan(3);
        }
        assertThat(at[1]).isEqualTo(65);
    }
}
