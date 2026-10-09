package de.raindancer.modules.invsnap.rules;

import de.raindancer.core.social.economy.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ItemPremiumRuleTest {

    private final ItemPremiumRule rule = new ItemPremiumRule();

    @Test
    @DisplayName("a percent of the value plus a flat price")
    void percentPlusFlat() {
        assertThat(rule.premium(Money.of(10_000), 10, Money.of(50), Money.ZERO)).isEqualTo(Money.of(1_050));
    }

    @Test
    @DisplayName("never under the floor")
    void floor() {
        assertThat(rule.premium(Money.of(100), 10, Money.ZERO, Money.of(500))).isEqualTo(Money.of(500));
        assertThat(rule.premium(Money.of(100_000), 10, Money.ZERO, Money.of(500))).isEqualTo(Money.of(10_000));
    }

    @Test
    @DisplayName("an item with no value and no flat or floor costs nothing")
    void nothing() {
        assertThat(rule.premium(Money.ZERO, 10, Money.ZERO, Money.ZERO).isZero()).isTrue();
        assertThat(rule.premium(null, 0, null, null).isZero()).isTrue();
    }
}
