package de.raindancer.modules.essentials.rules;

import de.raindancer.core.platform.rule.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class FunRuleTest {

    private final FunRule rule = new FunRule();

    @Test
    @DisplayName("switched off, it says so and names the command")
    void switchedOff() {
        Verdict verdict = rule.judge(new FunRule.Ask("roast", false, Optional.empty(), false));
        assertThat(verdict.isRefused()).isTrue();
        assertThat(verdict.reason()).isEqualTo("essentials.fun.switched-off");
        assertThat(verdict.detail()).isEqualTo("roast");
    }

    @Test
    @DisplayName("still cooling down, it says how long, rounded up so it never says 0")
    void coolingDown() {
        Verdict verdict = rule.judge(new FunRule.Ask("joke", true, Optional.of(Duration.ofMillis(4_200)), false));
        assertThat(verdict.isRefused()).isTrue();
        assertThat(verdict.reason()).isEqualTo("essentials.fun.cooling-down");
        assertThat(verdict.detail()).isEqualTo("5");
        assertThat(rule.judge(new FunRule.Ask("joke", true, Optional.of(Duration.ofMillis(10)), false)).detail())
                .isEqualTo("1");
    }

    @Test
    @DisplayName("whoever may skip the wait skips it, but a switched-off command stays off for them too")
    void bypass() {
        assertThat(rule.judge(new FunRule.Ask("roast", true, Optional.of(Duration.ofSeconds(9)), true)).isAllowed())
                .isTrue();
        assertThat(rule.judge(new FunRule.Ask("roast", false, Optional.empty(), true)).isAllowed()).isFalse();
    }

    @Test
    @DisplayName("on and ready, it is allowed")
    void ready() {
        assertThat(rule.judge(new FunRule.Ask("roast", true, Optional.empty(), false)).isAllowed()).isTrue();
    }
}
