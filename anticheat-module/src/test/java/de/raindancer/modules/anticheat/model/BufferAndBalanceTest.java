package de.raindancer.modules.anticheat.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class BufferAndBalanceTest {

    private static final long MS = 1_000_000L;

    @Test
    @DisplayName("a buffer flags only once evidence piles past its limit, and clean samples wear it down")
    void buffer() {
        Buffer buffer = new Buffer(3, 0.5);
        assertThat(buffer.fail(1)).isFalse();
        assertThat(buffer.fail(1)).isFalse();
        assertThat(buffer.fail(1)).isFalse();
        assertThat(buffer.fail(1)).isTrue();
        buffer.pass();
        buffer.pass();
        assertThat(buffer.value()).isCloseTo(3, within(1e-9));
        assertThat(buffer.fail(0.1)).isTrue();
    }

    @Test
    @DisplayName("a buffer cannot grow without bound, so a cheater who stops is clean again soon")
    void bufferCapped() {
        Buffer buffer = new Buffer(2, 1);
        for (int i = 0; i < 1000; i++) {
            buffer.fail(1);
        }
        assertThat(buffer.value()).isLessThanOrEqualTo(8);
    }

    @Test
    @DisplayName("twenty ticks a second stays level")
    void timerLevel() {
        TimerBalance balance = new TimerBalance(1000);
        long t = 0;
        for (int i = 0; i < 200; i++) {
            balance.tick(t);
            t += 50 * MS;
        }
        assertThat(balance.balance()).isCloseTo(0, within(1e-6));
    }

    @Test
    @DisplayName("a client at 1.5x climbs 17 ms a tick")
    void timerFast() {
        TimerBalance balance = new TimerBalance(1000);
        long t = 0;
        for (int i = 0; i < 31; i++) {
            balance.tick(t);
            t += (long) (33.333 * MS);
        }
        assertThat(balance.balance()).isGreaterThan(450);
    }

    @Test
    @DisplayName("a lag spike then a burst nets out, as long as the spike was within the credit")
    void lagThenBurst() {
        TimerBalance balance = new TimerBalance(1000);
        long t = 0;
        balance.tick(t);
        t += 800 * MS;
        balance.tick(t);
        for (int i = 0; i < 15; i++) {
            t += 1 * MS;
            balance.tick(t);
        }
        assertThat(balance.balance()).isLessThan(50);
    }

    @Test
    @DisplayName("standing still cannot bank more than the credit")
    void noBanking() {
        TimerBalance balance = new TimerBalance(500);
        balance.tick(0);
        balance.tick(60_000 * MS);
        assertThat(balance.balance()).isEqualTo(-500);
    }

    @Test
    @DisplayName("samples keep the newest, oldest first")
    void samples() {
        Samples samples = new Samples(3);
        for (int i = 1; i <= 5; i++) {
            samples.add(i);
        }
        assertThat(samples.toArray()).containsExactly(3, 4, 5);
        assertThat(samples.last()).isEqualTo(5);
        assertThat(samples.full()).isTrue();
    }

    @Test
    @DisplayName("the burst of ticks queued up while a join or teleport loads is not a fast clock")
    void burstAfterReset() {
        TimerBalance balance = new TimerBalance(1000);
        balance.reset();
        long t = 0;
        for (int i = 0; i < 6; i++) {
            balance.tick(t);
            t += 1 * MS;
        }
        assertThat(balance.balance()).isLessThan(0);
        for (int i = 0; i < 400; i++) {
            t += 33 * MS;
            balance.tick(t);
        }
        assertThat(balance.balance()).as("a really fast clock still climbs").isGreaterThan(1000);
    }
}
