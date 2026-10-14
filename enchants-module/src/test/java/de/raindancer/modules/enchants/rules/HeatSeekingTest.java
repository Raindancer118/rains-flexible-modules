package de.raindancer.modules.enchants.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class HeatSeekingTest {

    private static final double[] EYE = {0, 65.6, 0};
    /** North: towards -z. */
    private static final double[] NORTH = {0, 0, -1};

    private static HeatSeeking.Candidate at(double x, double y, double z) {
        return new HeatSeeking.Candidate(UUID.randomUUID(), x, y, z);
    }

    @Test
    @DisplayName("the target is the one nearest the line you aim along, if it is inside the cone and the range")
    void nearestTheAim() {
        HeatSeeking.Candidate offToTheSide = at(6, 65, -20);
        HeatSeeking.Candidate nearlyOnTheLine = at(1, 65, -30);
        HeatSeeking.Candidate behind = at(0, 65, 10);
        HeatSeeking.Candidate tooFar = at(0, 65, -200);

        assertThat(HeatSeeking.pick(EYE, NORTH, List.of(offToTheSide, nearlyOnTheLine, behind, tooFar), 26, 80))
                .hasValue(nearlyOnTheLine.id());
    }

    @Test
    @DisplayName("nothing inside the cone: no lock, the projectile flies as the game throws it")
    void nothingToLockOn() {
        HeatSeeking.Candidate wide = at(20, 65, -10);

        assertThat(HeatSeeking.pick(EYE, NORTH, List.of(wide), 10, 48)).isEmpty();
    }

    @Test
    @DisplayName("each level aims wider, reaches farther and turns harder")
    void levels() {
        assertThat(HeatSeeking.cone(1)).isLessThan(HeatSeeking.cone(2));
        assertThat(HeatSeeking.cone(2)).isLessThan(HeatSeeking.cone(3));
        assertThat(HeatSeeking.range(1)).isLessThan(HeatSeeking.range(3));
        assertThat(HeatSeeking.turn(1)).isLessThan(HeatSeeking.turn(3));
    }

    @Test
    @DisplayName("steering keeps at least the launch speed and turns towards the target")
    void steer() {
        double[] velocity = {0, 0, -3};
        double[] next = HeatSeeking.steer(velocity, new double[]{0, 65, 0}, new double[]{10, 65, 0}, 0.5, 3);

        assertThat(Math.sqrt(next[0] * next[0] + next[1] * next[1] + next[2] * next[2])).isCloseTo(3, within(1e-9));
        assertThat(next[0]).isPositive();
        assertThat(next[2]).isGreaterThan(-3);
    }

    @Test
    @DisplayName("an arrow shot straight north reaches a target well off to the side, at every level")
    void missileLock() {
        for (int level = 1; level <= 3; level++) {
            double[] position = {0, 65.6, 0};
            double[] velocity = {0, 0, -3};
            double[] target = {6, 65.9, -20};
            boolean hit = false;
            for (int tick = 0; tick < 100 && !hit; tick++) {
                velocity = HeatSeeking.steer(velocity, position, target, HeatSeeking.turn(level), 3);
                for (int axis = 0; axis < 3; axis++) {
                    position[axis] += velocity[axis];
                }
                double dx = position[0] - target[0];
                double dy = position[1] - target[1];
                double dz = position[2] - target[2];
                hit = Math.sqrt(dx * dx + dy * dy + dz * dz) < 1.0;
            }
            assertThat(hit).as("level " + level).isTrue();
        }
    }

    @Test
    @DisplayName("III forgives a wide miss: 45 degrees off the aim, and turns hard enough to still get there")
    void threeForgives() {
        assertThat(HeatSeeking.cone(3)).isEqualTo(45);
        assertThat(HeatSeeking.turn(3)).isEqualTo(0.85);
        assertThat(HeatSeeking.cone(2)).isEqualTo(18);
    }
}
