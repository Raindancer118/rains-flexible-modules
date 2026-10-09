package de.raindancer.modules.invsnap.rules;

import de.raindancer.core.social.economy.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InsurancePremiumRuleTest {

    private final InsurancePremiumRule rule = new InsurancePremiumRule();

    @Test
    @DisplayName("nothing set costs nothing")
    void defaultsAreFree() {
        assertThat(rule.premium(Money.of(100_000), 0, Money.ZERO, Money.ZERO)).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("a percentage of the inventory's value")
    void percentOfValue() {
        assertThat(rule.premium(Money.of(100_000), 10, Money.ZERO, Money.ZERO)).isEqualTo(Money.of(10_000));
    }

    @Test
    @DisplayName("the flat price is added to the percentage")
    void flatPlusPercent() {
        assertThat(rule.premium(Money.of(100_000), 10, Money.of(500), Money.ZERO)).isEqualTo(Money.of(10_500));
    }

    @Test
    @DisplayName("the flat price alone is charged even for an empty inventory")
    void flatOnly() {
        assertThat(rule.premium(Money.ZERO, 10, Money.of(500), Money.ZERO)).isEqualTo(Money.of(500));
    }

    @Test
    @DisplayName("a positive cap limits the total, zero means no cap")
    void capLimits() {
        assertThat(rule.premium(Money.of(100_000), 10, Money.of(500), Money.of(3_000))).isEqualTo(Money.of(3_000));
        assertThat(rule.premium(Money.of(100), 10, Money.ZERO, Money.of(3_000))).isEqualTo(Money.of(10));
    }

    @Test
    @DisplayName("nonsense input never produces a negative premium")
    void neverNegative() {
        assertThat(rule.premium(Money.of(100_000), -5, Money.of(-100), Money.ZERO)).isEqualTo(Money.ZERO);
        assertThat(rule.premium(null, 10, null, null)).isEqualTo(Money.ZERO);
    }
}
