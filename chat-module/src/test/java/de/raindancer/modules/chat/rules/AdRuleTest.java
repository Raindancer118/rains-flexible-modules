package de.raindancer.modules.chat.rules;

import de.raindancer.core.platform.rule.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdRuleTest {

    private final AdRule rule = new AdRule();

    @Test
    @DisplayName("an ad is refused while ads are switched off")
    void off() {
        Verdict verdict = rule.judge(false, "buy my cobble", 120, Long.MAX_VALUE, 600);

        assertThat(verdict.reason()).isEqualTo("chat.ad.off");
    }

    @Test
    @DisplayName("an empty ad is refused")
    void empty() {
        assertThat(rule.judge(true, "   ", 120, Long.MAX_VALUE, 600).reason()).isEqualTo("chat.ad.empty");
    }

    @Test
    @DisplayName("an ad longer than the most is refused and says the most")
    void tooLong() {
        Verdict verdict = rule.judge(true, "x".repeat(121), 120, Long.MAX_VALUE, 600);

        assertThat(verdict.reason()).isEqualTo("chat.ad.too-long");
        assertThat(verdict.detail()).isEqualTo("120");
        assertThat(rule.judge(true, "x".repeat(120), 120, Long.MAX_VALUE, 600).isAllowed()).isTrue();
    }

    @Test
    @DisplayName("a second ad inside the cooldown is refused and says how many seconds are left, rounded up")
    void cooldown() {
        Verdict verdict = rule.judge(true, "hello", 120, 599_001, 600);

        assertThat(verdict.reason()).isEqualTo("chat.ad.cooldown");
        assertThat(verdict.detail()).isEqualTo("1");
        assertThat(rule.judge(true, "hello", 120, 600_000, 600).isAllowed()).isTrue();
    }

    @Test
    @DisplayName("a cooldown of zero never holds an ad back")
    void noCooldown() {
        assertThat(rule.judge(true, "hello", 120, 0, 0).isAllowed()).isTrue();
    }
}
