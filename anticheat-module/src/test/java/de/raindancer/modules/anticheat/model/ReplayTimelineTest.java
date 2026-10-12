package de.raindancer.modules.anticheat.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ReplayTimelineTest {

    private static ReplayFrame at(long millis, double x, float yaw) {
        return new ReplayFrame(millis, "world", x, 64, 0, yaw, 0, true);
    }

    private final ReplayTimeline timeline = new ReplayTimeline(List.of(at(1000, 0, 0), at(1100, 1, 90), at(1300, 3, 170)));

    @Test
    @DisplayName("a replay lasts from its first frame to its last")
    void length() {
        assertThat(timeline.millis()).isEqualTo(300);
    }

    @Test
    @DisplayName("between two frames the position is in between, at the same pace as it happened")
    void interpolates() {
        ReplayFrame half = timeline.at(50);
        assertThat(half.x()).isCloseTo(0.5, within(1e-9));
        assertThat(half.yaw()).isCloseTo(45f, within(1e-4f));
        assertThat(timeline.at(200).x()).isCloseTo(2.0, within(1e-9));
    }

    @Test
    @DisplayName("turning across south takes the short way round")
    void shortestTurn() {
        ReplayTimeline turning = new ReplayTimeline(List.of(at(0, 0, 170), at(100, 0, -170)));
        float middle = turning.at(50).yaw();
        assertThat(Math.abs(middle)).isGreaterThan(175f);
    }

    @Test
    @DisplayName("before the start and after the end it holds the first and last frame")
    void clamps() {
        assertThat(timeline.at(-10).x()).isZero();
        assertThat(timeline.at(10_000).x()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("the alert is the last moment of a replay")
    void alert() {
        assertThat(timeline.isAlert(299)).isFalse();
        assertThat(timeline.isAlert(300)).isTrue();
    }
}
