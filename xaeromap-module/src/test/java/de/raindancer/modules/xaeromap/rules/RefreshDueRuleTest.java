package de.raindancer.modules.xaeromap.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** That the refresh clock keeps to the interval, and that a fresh server does not wait for it. */
class RefreshDueRuleTest {

    private final RefreshDueRule rule = new RefreshDueRule();
    private final Instant now = Instant.parse("2026-08-20T12:00:00Z");

    @Test
    @DisplayName("a server that has never refreshed is due at once")
    void thefirstOneIsImmediate() {
        assertThat(rule.isDue(null, now, Duration.ofSeconds(5)))
                .as("waiting one interval before the first refresh means every player who joins in "
                        + "that window sees an empty map")
                .isTrue();
    }

    @Test
    @DisplayName("exactly one interval later is due; a moment before it is not")
    void theBoundaryIsInclusive() {
        Duration interval = Duration.ofSeconds(5);

        assertThat(rule.isDue(now.minusSeconds(5), now, interval)).isTrue();
        assertThat(rule.isDue(now.minusMillis(4_999), now, interval)).isFalse();
        assertThat(rule.isDue(now.minusSeconds(6), now, interval)).isTrue();
    }

    @Test
    @DisplayName("a refresh that has just happened is not due again")
    void itDoesNotRunTwice() {
        assertThat(rule.isDue(now, now, Duration.ofSeconds(5))).isFalse();
    }
}
