package de.raindancer.modules.manhunt.service;

import de.raindancer.modules.manhunt.service.SpawnCircle.Spot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Where everybody stands when a hunt begins: evenly around one circle, the circle as large as the
 * roster needs. Asked for directly — "everyone should be spawned in a circle, depending on the count
 * of people participating".
 */
class SpawnCircleTest {

    private static double gap(Spot a, Spot b) {
        return Math.hypot(a.x() - b.x(), a.z() - b.z());
    }

    @Test
    @DisplayName("one spot per participant, every one the same distance from the centre")
    void everybodyOnOneCircle() {
        List<Spot> spots = SpawnCircle.around(100, -50, 8, 4, 4);

        assertThat(spots).hasSize(8);
        double radius = Math.hypot(spots.getFirst().x() - 100, spots.getFirst().z() + 50);
        for (Spot spot : spots) {
            assertThat(Math.hypot(spot.x() - 100, spot.z() + 50)).isCloseTo(radius, within(1e-9));
        }
    }

    @Test
    @DisplayName("the circle grows with the roster, so neighbours keep the same gap")
    void circleGrowsWithTheRoster() {
        List<Spot> few = SpawnCircle.around(0, 0, 10, 4, 1);
        List<Spot> many = SpawnCircle.around(0, 0, 40, 4, 1);

        assertThat(gap(few.get(0), few.get(1))).isCloseTo(gap(many.get(0), many.get(1)), within(0.05));
        assertThat(Math.hypot(many.getFirst().x(), many.getFirst().z()))
                .isGreaterThan(Math.hypot(few.getFirst().x(), few.getFirst().z()));
    }

    @Test
    @DisplayName("a small roster is never squeezed closer to the centre than the minimum radius")
    void smallRosterKeepsTheMinimumRadius() {
        List<Spot> two = SpawnCircle.around(0, 0, 2, 4, 6);

        for (Spot spot : two) {
            assertThat(Math.hypot(spot.x(), spot.z())).isCloseTo(6, within(1e-9));
        }
    }

    @Test
    @DisplayName("everybody starts facing the middle")
    void everybodyFacesTheCentre() {
        for (Spot spot : SpawnCircle.around(0, 0, 12, 4, 4)) {
            // Minecraft yaw: 0 looks towards +Z, 90 towards -X. The facing vector from that yaw has
            // to point from the spot back to the centre.
            double lookX = -Math.sin(Math.toRadians(spot.yaw()));
            double lookZ = Math.cos(Math.toRadians(spot.yaw()));
            double towardsX = -spot.x();
            double towardsZ = -spot.z();
            double length = Math.hypot(towardsX, towardsZ);
            assertThat(lookX).isCloseTo(towardsX / length, within(1e-6));
            assertThat(lookZ).isCloseTo(towardsZ / length, within(1e-6));
        }
    }

    @Test
    @DisplayName("nobody at all is no spots, not a division by zero")
    void emptyRosterIsNoSpots() {
        assertThat(SpawnCircle.around(0, 0, 0, 4, 4)).isEmpty();
    }
}
