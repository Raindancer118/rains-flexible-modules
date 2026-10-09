package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GambleInsuranceRuleTest {

    private final GambleInsuranceRule rule = new GambleInsuranceRule();

    @Test
    @DisplayName("the premium is a share of the stake, rounded up — the house never undercharges")
    void premium() {
        assertThat(rule.premium(Money.of(1_000), 30)).isEqualTo(Money.of(300));
        assertThat(rule.premium(Money.of(1_001), 30)).isEqualTo(Money.of(301));
        assertThat(rule.premium(Money.of(1_000), 0)).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("a loss pays back a share of what was lost, rounded down; a win or a draw pays nothing back")
    void payback() {
        assertThat(rule.payback(Money.of(1_000), Money.ZERO, 50)).isEqualTo(Money.of(500));
        assertThat(rule.payback(Money.of(1_000), Money.of(400), 50)).as("half lost back").isEqualTo(Money.of(300));
        assertThat(rule.payback(Money.of(1_001), Money.ZERO, 50)).isEqualTo(Money.of(500));
        assertThat(rule.payback(Money.of(1_000), Money.of(1_000), 50)).isEqualTo(Money.ZERO);
        assertThat(rule.payback(Money.of(1_000), Money.of(2_000), 50)).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("a payback is never more than the stake, whatever the owner wrote")
    void bounded() {
        assertThat(rule.payback(Money.of(1_000), Money.ZERO, 250)).isEqualTo(Money.of(1_000));
    }
}
