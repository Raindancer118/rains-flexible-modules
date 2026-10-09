package de.raindancer.modules.performance.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class TickWindowTest {

    @Test
    @DisplayName("mean, worst and the 95th percentile of the ticks it holds")
    void statistics() {
        TickWindow window = new TickWindow(100);
        for (int i = 1; i <= 100; i++) {
            window.add(i);
        }

        assertThat(window.mean()).isCloseTo(50.5, within(1e-9));
        assertThat(window.worst()).isEqualTo(100);
        assertThat(window.percentile95()).isEqualTo(95);
        assertThat(window.size()).isEqualTo(100);
    }

    @Test
    @DisplayName("only the newest ticks count once it is full")
    void rolls() {
        TickWindow window = new TickWindow(3);
        window.add(200);
        window.add(10);
        window.add(10);
        window.add(10);

        assertThat(window.worst()).isEqualTo(10);
        assertThat(window.mean()).isEqualTo(10);
    }

    @Test
    @DisplayName("ticks per second: 20 while a tick fits in 50 ms, fewer once it does not")
    void tps() {
        TickWindow fast = new TickWindow(20);
        TickWindow slow = new TickWindow(20);
        for (int i = 0; i < 20; i++) {
            fast.add(12);
            slow.add(64);
        }

        assertThat(fast.tps()).isEqualTo(20.0);
        assertThat(slow.tps()).isCloseTo(1000.0 / 64, within(1e-9));
    }

    @Test
    @DisplayName("empty, it claims a healthy server rather than dividing by nothing")
    void empty() {
        TickWindow window = new TickWindow(10);

        assertThat(window.mean()).isZero();
        assertThat(window.tps()).isEqualTo(20.0);
        assertThat(window.worst()).isZero();
    }

    @Test
    @DisplayName("a window of nothing is a mistake")
    void size() {
        assertThatThrownBy(() -> new TickWindow(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
