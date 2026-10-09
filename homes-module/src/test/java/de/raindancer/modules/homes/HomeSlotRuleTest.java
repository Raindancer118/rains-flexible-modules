package de.raindancer.modules.homes;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.homes.rules.HomeSlotRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HomeSlotRuleTest {

    private final HomeSlotRule rule = new HomeSlotRule();

    @Test
    @DisplayName("a price of zero means buying is off")
    void zeroIsOff() {
        assertThat(rule.isOn(Money.ZERO)).isFalse();
        assertThat(rule.isOn(Money.of(1))).isTrue();
    }

    @Test
    @DisplayName("without growth every slot costs the same")
    void flatPrice() {
        Money base = Money.of(1000);
        assertThat(rule.priceOfNext(base, 0, 0)).isEqualTo(Money.of(1000));
        assertThat(rule.priceOfNext(base, 0, 7)).isEqualTo(Money.of(1000));
    }

    @Test
    @DisplayName("each further slot costs that much more than the previous one")
    void growthCompounds() {
        Money base = Money.of(1000);
        assertThat(rule.priceOfNext(base, 50, 0)).isEqualTo(Money.of(1000));
        assertThat(rule.priceOfNext(base, 50, 1)).isEqualTo(Money.of(1500));
        assertThat(rule.priceOfNext(base, 50, 2)).isEqualTo(Money.of(2250));
        assertThat(rule.priceOfNext(base, 50, 3)).isEqualTo(Money.of(3375));
    }

    @Test
    @DisplayName("a percentage like 29 is exact, not a cent short from floating point")
    void exactPercentages() {
        assertThat(rule.priceOfNext(Money.of(100), 29, 1)).isEqualTo(Money.of(129));
    }

    @Test
    @DisplayName("rounding is down, never a cent nobody paid")
    void roundsDown() {
        assertThat(rule.priceOfNext(Money.of(101), 10, 1)).isEqualTo(Money.of(111));
        assertThat(rule.priceOfNext(Money.of(105), 10, 1)).isEqualTo(Money.of(115));
    }

    @Test
    @DisplayName("a huge count saturates instead of overflowing")
    void saturates() {
        assertThat(rule.priceOfNext(Money.of(1_000_000), 100, 500).minor()).isPositive();
    }

    @Test
    @DisplayName("a negative count or growth is read as none")
    void clampsNonsense() {
        assertThat(rule.priceOfNext(Money.of(1000), -20, -3)).isEqualTo(Money.of(1000));
    }

    @Test
    @DisplayName("zero as the limit means no limit; otherwise it is the most that may be bought")
    void mostBought() {
        assertThat(rule.mayBuyAnother(500, 0)).isTrue();
        assertThat(rule.mayBuyAnother(2, 3)).isTrue();
        assertThat(rule.mayBuyAnother(3, 3)).isFalse();
    }
}
