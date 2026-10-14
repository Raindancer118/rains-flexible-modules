package de.raindancer.modules.enchants.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class AutopilotTest {

    private static List<Autopilot.Point> fly(Autopilot.Plan plan) {
        List<Autopilot.Point> points = new ArrayList<>();
        for (int tick = 0; tick <= plan.ticks(); tick++) {
            points.add(plan.at(tick));
        }
        return points;
    }

    @Test
    @DisplayName("a long flight: launch straight up, cruise high, dive onto the place — from where it started to exactly there")
    void longFlight() {
        Autopilot.Plan plan = Autopilot.plan(new double[]{0, 64, 0}, new double[]{300, 70, 40}, 300);
        List<Autopilot.Point> points = fly(plan);
        assertThat(points.getFirst().x()).isEqualTo(0);
        assertThat(points.getFirst().y()).isEqualTo(64);
        Autopilot.Point last = points.getLast();
        assertThat(last.x()).isCloseTo(300, within(1e-6));
        assertThat(last.y()).isCloseTo(70, within(1e-6));
        assertThat(last.z()).isCloseTo(40, within(1e-6));
        assertThat(points.stream().mapToDouble(Autopilot.Point::y).max().orElseThrow()).isCloseTo(110, within(1e-6));
        assertThat(points.stream().map(Autopilot.Point::phase).distinct().toList())
                .containsExactly(Autopilot.Phase.LAUNCH, Autopilot.Phase.CRUISE, Autopilot.Phase.DIVE);
        for (int i = 1; i < points.size(); i++) {
            Autopilot.Point a = points.get(i - 1);
            Autopilot.Point b = points.get(i);
            double step = Math.sqrt(Math.pow(b.x() - a.x(), 2) + Math.pow(b.y() - a.y(), 2) + Math.pow(b.z() - a.z(), 2));
            assertThat(step).as("never faster than a dive, so the world can load in time").isLessThanOrEqualTo(Autopilot.DIVE_SPEED + 1e-6);
        }
        assertThat(points.get(1).pitch()).as("the launch looks up").isLessThan(-45);
        assertThat(points.get(points.size() - 2).pitch()).as("the dive looks down").isGreaterThan(20);
    }

    @Test
    @DisplayName("a short hop has no cruise; the height never goes past the world's ceiling")
    void shortAndHigh() {
        List<Autopilot.Point> hop = fly(Autopilot.plan(new double[]{0, 64, 0}, new double[]{10, 64, 0}, 300));
        assertThat(hop.stream().map(Autopilot.Point::phase).distinct().toList())
                .containsExactly(Autopilot.Phase.LAUNCH, Autopilot.Phase.DIVE);
        List<Autopilot.Point> capped = fly(Autopilot.plan(new double[]{0, 290, 0}, new double[]{500, 300, 0}, 310));
        assertThat(capped.stream().mapToDouble(Autopilot.Point::y).max().orElseThrow()).isLessThanOrEqualTo(310);
    }

    @Test
    @DisplayName("the camera hangs behind and above, looking at the flier")
    void camera() {
        Autopilot.Point at = new Autopilot.Point(100, 100, 0, 0, 0, Autopilot.Phase.CRUISE);
        Autopilot.Point camera = Autopilot.camera(at, new double[]{1, 0});
        assertThat(camera.x()).isLessThan(100);
        assertThat(camera.y()).isGreaterThan(100);
        assertThat(camera.pitch()).as("looking down at it").isPositive();
        assertThat((double) camera.yaw()).as("facing +x").isCloseTo(-90, within(1e-3));
    }
}
