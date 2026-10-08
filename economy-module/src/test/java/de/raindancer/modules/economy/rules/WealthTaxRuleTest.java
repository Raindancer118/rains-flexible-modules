package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("what the wealth tax takes")
class WealthTaxRuleTest {

    private final WealthTaxRule rule = new WealthTaxRule();

    @Test
    @DisplayName("a share of what is above the allowance, rounded down")
    void owed() {
        assertThat(rule.owed(Money.of(10_000), 1.0, Money.ZERO)).isEqualTo(Money.of(100));
        assertThat(rule.owed(Money.of(10_000), 1.0, Money.of(5_000))).isEqualTo(Money.of(50));
        assertThat(rule.owed(Money.of(4_000), 1.0, Money.of(5_000))).as("below the allowance").isEqualTo(Money.ZERO);
        assertThat(rule.owed(Money.of(99), 1.0, Money.ZERO)).as("rounded down").isEqualTo(Money.ZERO);
        assertThat(rule.owed(Money.of(1_000), 250, Money.ZERO)).as("never more than everything")
                .isEqualTo(Money.of(1_000));
        assertThat(rule.owed(Money.of(1_000), -5, Money.ZERO)).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("due once the interval has passed since the last run")
    void due() {
        assertThat(rule.due(0, 1_000_000, 24)).as("never run: the clock starts now, nobody is taxed yet").isFalse();
        assertThat(rule.due(1_000, 1_000 + 24 * 3_600_000L, 24)).isTrue();
        assertThat(rule.due(1_000, 1_000 + 24 * 3_600_000L - 1, 24)).isFalse();
    }
}
