package de.raindancer.modules.ctf.rules;

import de.raindancer.modules.ctf.model.Challenge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("daily challenges")
class ChallengeRuleTest {

    private final ChallengeRule rule = new ChallengeRule();

    @Test
    @DisplayName("the same day gives everybody the same challenges, a different day others")
    void sameDay() {
        LocalDate day = LocalDate.of(2026, 10, 16);
        List<Challenge> today = rule.today(day, 3);
        assertThat(today).hasSize(3).doesNotHaveDuplicates();
        assertThat(rule.today(day, 3)).isEqualTo(today);
        boolean someDayDiffers = false;
        for (int plus = 1; plus < 10; plus++) {
            someDayDiffers |= !rule.today(day.plusDays(plus), 3).equals(today);
        }
        assertThat(someDayDiffers).isTrue();
    }

    @Test
    @DisplayName("never more than there are")
    void bounded() {
        assertThat(rule.today(LocalDate.of(2026, 1, 1), 99)).hasSize(Challenge.values().length);
        assertThat(rule.today(LocalDate.of(2026, 1, 1), 0)).isEmpty();
    }

    @Test
    @DisplayName("progress is capped at the goal, and done is reported once")
    void progress() {
        ChallengeRule.Step step = rule.step(Challenge.CAPTURE, 2, 1);
        assertThat(step.now()).isEqualTo(3);
        assertThat(step.justDone()).isTrue();
        ChallengeRule.Step again = rule.step(Challenge.CAPTURE, 3, 2);
        assertThat(again.now()).isEqualTo(3);
        assertThat(again.justDone()).isFalse();
    }
}
