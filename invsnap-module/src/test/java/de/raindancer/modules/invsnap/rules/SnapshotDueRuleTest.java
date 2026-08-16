package de.raindancer.modules.invsnap.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("whether an automatic snapshot is due")
class SnapshotDueRuleTest {

    private final SnapshotDueRule rule = new SnapshotDueRule();

    @Test
    @DisplayName("a distant-past last snapshot (nothing recorded yet) is due at once")
    void neverTakenIsDueAtOnce() {
        assertThat(rule.isDue(Instant.EPOCH, Instant.now(), Duration.ofMinutes(5))).isTrue();
    }

    @Test
    @DisplayName("exactly the interval having passed counts as due")
    void exactlyTheIntervalIsDue() {
        Instant last = Instant.now().minus(Duration.ofMinutes(5));
        assertThat(rule.isDue(last, last.plus(Duration.ofMinutes(5)), Duration.ofMinutes(5))).isTrue();
    }

    @Test
    @DisplayName("not enough time has passed yet")
    void notEnoughTimeYet() {
        Instant now = Instant.now();
        Instant last = now.minus(Duration.ofMinutes(2));
        assertThat(rule.isDue(last, now, Duration.ofMinutes(5))).isFalse();
    }

    @Test
    @DisplayName("missing arguments never claim it is due — the safe default is 'not yet'")
    void missingArgumentsAreNeverDue() {
        Instant now = Instant.now();
        assertThat(rule.isDue(null, now, Duration.ofMinutes(5))).isFalse();
        assertThat(rule.isDue(now, null, Duration.ofMinutes(5))).isFalse();
        assertThat(rule.isDue(now, now, null)).isFalse();
    }
}
