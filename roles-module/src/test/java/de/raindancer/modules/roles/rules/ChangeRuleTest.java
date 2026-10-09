package de.raindancer.modules.roles.rules;

import de.raindancer.modules.roles.model.Choice;
import de.raindancer.modules.roles.model.ChangeVerdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ChangeRuleTest {

    private final ChangeRule rule = new ChangeRule();
    private final UUID player = UUID.randomUUID();
    private static final Duration THREE_DAYS = Duration.ofDays(3);
    private static final long DAY = Duration.ofDays(1).toMillis();

    private Optional<Choice> chose(String role, long at) {
        return Optional.of(new Choice(player, role, at));
    }

    @Test
    @DisplayName("the first role is picked freely, at any time")
    void first() {
        ChangeVerdict verdict = rule.decide(Optional.empty(), "cook", 1000, THREE_DAYS, false);
        assertThat(verdict.allowed()).isTrue();
        assertThat(verdict.reason()).isEqualTo(ChangeVerdict.Reason.FIRST);
    }

    @Test
    @DisplayName("a change waits three days from the last choice, and says how long is left")
    void waits() {
        ChangeVerdict early = rule.decide(chose("cook", 0), "builder", 2 * DAY + 1000, THREE_DAYS, false);
        assertThat(early.allowed()).isFalse();
        assertThat(early.reason()).isEqualTo(ChangeVerdict.Reason.WAIT);
        assertThat(early.left()).isEqualTo(Duration.ofDays(1).minusSeconds(1));
        ChangeVerdict onTime = rule.decide(chose("cook", 0), "builder", 3 * DAY, THREE_DAYS, false);
        assertThat(onTime.allowed()).isTrue();
        assertThat(onTime.reason()).isEqualTo(ChangeVerdict.Reason.WAITED);
    }

    @Test
    @DisplayName("an admin bypassing changes at once, as often as they like")
    void bypass() {
        ChangeVerdict verdict = rule.decide(chose("cook", 0), "builder", 1, THREE_DAYS, true);
        assertThat(verdict.allowed()).isTrue();
        assertThat(verdict.reason()).isEqualTo(ChangeVerdict.Reason.BYPASS);
    }

    @Test
    @DisplayName("picking the role you have is no change, and costs no wait")
    void same() {
        ChangeVerdict verdict = rule.decide(chose("cook", 0), "cook", 4 * DAY, THREE_DAYS, false);
        assertThat(verdict.allowed()).isFalse();
        assertThat(verdict.reason()).isEqualTo(ChangeVerdict.Reason.SAME);
    }

    @Test
    @DisplayName("a wait of zero means changing whenever; a clock that went backwards still waits, never forever")
    void edges() {
        assertThat(rule.decide(chose("cook", 0), "builder", 0, Duration.ZERO, false).allowed()).isTrue();
        ChangeVerdict backwards = rule.decide(chose("cook", 10 * DAY), "builder", 0, THREE_DAYS, false);
        assertThat(backwards.allowed()).isFalse();
        assertThat(backwards.left()).isLessThanOrEqualTo(THREE_DAYS);
    }

    @Test
    @DisplayName("when the next change is allowed, for showing in a menu")
    void nextChange() {
        assertThat(rule.left(chose("cook", 0), DAY, THREE_DAYS)).isEqualTo(Duration.ofDays(2));
        assertThat(rule.left(chose("cook", 0), 5 * DAY, THREE_DAYS)).isEqualTo(Duration.ZERO);
        assertThat(rule.left(Optional.empty(), 0, THREE_DAYS)).isEqualTo(Duration.ZERO);
    }
}
