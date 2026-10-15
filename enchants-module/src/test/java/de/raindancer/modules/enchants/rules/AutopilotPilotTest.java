package de.raindancer.modules.enchants.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.IntBinaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class AutopilotPilotTest {

    /** Where one can stand in each column: the first free block above the land. */
    private static AutopilotPilot.Terrain land(IntBinaryOperator top) {
        return (x, z) -> OptionalInt.of(top.applyAsInt(x, z));
    }

    private static List<Autopilot.Point> fly(AutopilotPilot pilot, AutopilotPilot.Terrain terrain) {
        List<Autopilot.Point> points = new ArrayList<>();
        for (int tick = 0; tick < 5_000 && !pilot.landed(); tick++) {
            pilot.step(terrain).ifPresent(points::add);
        }
        assertThat(pilot.landed()).as("it gets there").isTrue();
        return points;
    }

    private static void neverThroughTheGround(List<Autopilot.Point> points, IntBinaryOperator top) {
        for (Autopilot.Point point : points) {
            int ground = top.applyAsInt((int) Math.floor(point.x()), (int) Math.floor(point.z()));
            assertThat(point.y()).as("at " + point.x() + " " + point.z()).isGreaterThanOrEqualTo(ground - 1e-6);
        }
    }

    @Test
    @DisplayName("over flat land: up, along low over it, a dive, and down exactly on the place")
    void flat() {
        IntBinaryOperator top = (x, z) -> 64;
        List<Autopilot.Point> points = fly(new AutopilotPilot(new double[]{0, 64, 0}, new double[]{300, 64, 40}, 300),
                land(top));
        neverThroughTheGround(points, top);
        Autopilot.Point last = points.getLast();
        assertThat(last.x()).isCloseTo(300, within(1e-6));
        assertThat(last.y()).isCloseTo(64, within(1e-6));
        assertThat(last.z()).isCloseTo(40, within(1e-6));
        assertThat(points.stream().mapToDouble(Autopilot.Point::y).max().orElseThrow()).as("low over flat land")
                .isBetween(70.0, 80.0);
        assertThat(points.stream().map(Autopilot.Point::phase).distinct().toList())
                .containsExactly(Autopilot.Phase.LAUNCH, Autopilot.Phase.CRUISE, Autopilot.Phase.DIVE);
        for (int i = 1; i < points.size(); i++) {
            Autopilot.Point a = points.get(i - 1);
            Autopilot.Point b = points.get(i);
            double step = Math.sqrt(Math.pow(b.x() - a.x(), 2) + Math.pow(b.y() - a.y(), 2) + Math.pow(b.z() - a.z(), 2));
            assertThat(step).as("never so fast the land ahead cannot load").isLessThanOrEqualTo(AutopilotPilot.FASTEST + 1e-6);
        }
    }

    @Test
    @DisplayName("a mountain on the way: over it, never through it, and low again in the valley behind it")
    void mountain() {
        IntBinaryOperator top = (x, z) -> x >= 150 && x < 200 ? 160 : 64;
        List<Autopilot.Point> points = fly(new AutopilotPilot(new double[]{0, 64, 0}, new double[]{400, 64, 0}, 300),
                land(top));
        neverThroughTheGround(points, top);
        assertThat(points.stream().filter(point -> point.x() > 150 && point.x() < 200).mapToDouble(Autopilot.Point::y).min()
                .orElseThrow()).as("clear over the top").isGreaterThan(160);
        assertThat(points.stream().filter(point -> point.x() > 330 && point.x() < 350).mapToDouble(Autopilot.Point::y).max()
                .orElseThrow()).as("down low again behind it").isLessThan(90);
    }

    @Test
    @DisplayName("a place under a roof: it lands on the roof above it")
    void underARoof() {
        IntBinaryOperator top = (x, z) -> x >= 95 && x <= 105 ? 80 : 64;
        AutopilotPilot pilot = new AutopilotPilot(new double[]{0, 64, 0}, new double[]{100, 64, 0}, 300);
        List<Autopilot.Point> points = fly(pilot, land(top));
        neverThroughTheGround(points, top);
        assertThat(points.getLast().y()).isCloseTo(80, within(1e-6));
    }

    @Test
    @DisplayName("land not loaded yet ahead: it waits where it is")
    void waits() {
        AutopilotPilot pilot = new AutopilotPilot(new double[]{0, 64, 0}, new double[]{300, 64, 0}, 300);
        AutopilotPilot.Terrain half = (x, z) -> x > 50 ? OptionalInt.empty() : OptionalInt.of(64);
        for (int tick = 0; tick < 200; tick++) {
            pilot.step(half);
        }
        assertThat(pilot.step(half)).isEmpty();
        assertThat(pilot.landed()).isFalse();
    }
}
