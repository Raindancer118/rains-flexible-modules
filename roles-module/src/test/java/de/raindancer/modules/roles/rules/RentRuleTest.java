package de.raindancer.modules.roles.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RentRuleTest {

    private static final long DAY = Duration.ofDays(1).toMillis();
    private final RentRule rule = new RentRule();

    @Test
    @DisplayName("rent is due from its due date on, not before")
    void due() {
        assertThat(rule.due(1000, 999)).isFalse();
        assertThat(rule.due(1000, 1000)).isTrue();
    }

    @Test
    @DisplayName("the next due date is thirty days after the last one")
    void next() {
        assertThat(rule.next(10 * DAY, 10 * DAY + 5)).isEqualTo(40 * DAY);
    }

    @Test
    @DisplayName("time spent away is not billed: a due date long past restarts thirty days from now")
    void away() {
        assertThat(rule.next(10 * DAY, 200 * DAY)).isEqualTo(230 * DAY);
    }

    @Test
    @DisplayName("a warning goes out in the last three days before rent is due, and not earlier")
    void warns() {
        assertThat(rule.warn(10 * DAY, 7 * DAY)).isTrue();
        assertThat(rule.warn(10 * DAY, 7 * DAY - 1)).isFalse();
        assertThat(rule.warn(10 * DAY, 10 * DAY)).isFalse();
    }
}
