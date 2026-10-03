package de.raindancer.modules.speedrun;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RunClockTest {

    @Test
    @DisplayName("reads the clock the way the action bar shows it")
    void clockFormat() {
        assertThat(RunClock.parse("42:05")).contains(Duration.ofMinutes(42).plusSeconds(5));
        assertThat(RunClock.parse("1:02:03")).contains(Duration.ofHours(1).plusMinutes(2).plusSeconds(3));
        assertThat(RunClock.parse("0")).contains(Duration.ZERO);
    }

    @Test
    @DisplayName("and the way somebody would say it")
    void units() {
        assertThat(RunClock.parse("1h30m")).contains(Duration.ofMinutes(90));
    }

    @Test
    @DisplayName("nonsense is refused, not guessed at")
    void nonsense() {
        assertThat(RunClock.parse("soon")).isEmpty();
        assertThat(RunClock.parse("1:75")).isEmpty();
        assertThat(RunClock.parse("-5:00")).isEmpty();
    }
}
