package de.raindancer.modules.jobs.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class LearningRuleTest {

    private final LearningRule rule = new LearningRule();
    private static final long DAY = Duration.ofDays(1).toMillis();
    private static final long FIVE_DAYS = 5 * DAY;

    @Test
    @DisplayName("a goal hit on its first day of five is too small: the next is bigger, at most twice as big")
    void tooEasy() {
        assertThat(rule.next(200, 200, 0, DAY, FIVE_DAYS, 10, 10_000)).isEqualTo(400);
    }

    @Test
    @DisplayName("a goal hit late but in time stays about the same; one hit at three quarters exactly, too")
    void aboutRight() {
        assertThat(rule.next(200, 200, 0, FIVE_DAYS * 3 / 4, FIVE_DAYS, 10, 10_000)).isEqualTo(200);
        assertThat(rule.next(200, 200, 0, FIVE_DAYS * 9 / 10, FIVE_DAYS, 10, 10_000)).isBetween(160, 170);
    }

    @Test
    @DisplayName("a goal missed is too big: the next is what was managed, aimed to be reached in time, at least half")
    void tooHard() {
        // 120 of 200 in five days: at that pace 75% of the time would make 90
        assertThat(rule.next(200, 120, 0, FIVE_DAYS, FIVE_DAYS, 10, 10_000)).isEqualTo(100);
        assertThat(rule.next(200, 180, 0, FIVE_DAYS, FIVE_DAYS, 10, 10_000)).isEqualTo(135);
        assertThat(rule.next(200, 0, 0, FIVE_DAYS, FIVE_DAYS, 10, 10_000)).isEqualTo(100);
    }

    @Test
    @DisplayName("never below the least or above the most an owner set")
    void bounds() {
        assertThat(rule.next(20, 0, 0, FIVE_DAYS, FIVE_DAYS, 15, 10_000)).isEqualTo(15);
        assertThat(rule.next(8000, 8000, 0, DAY / 24, FIVE_DAYS, 10, 10_000)).isEqualTo(10_000);
    }

    @Test
    @DisplayName("a goal finished in a minute counts as an hour, so one lucky hand-in cannot balloon the next")
    void tooFast() {
        assertThat(rule.next(200, 200, 0, 60_000, FIVE_DAYS, 10, 10_000)).isEqualTo(400);
    }
}
