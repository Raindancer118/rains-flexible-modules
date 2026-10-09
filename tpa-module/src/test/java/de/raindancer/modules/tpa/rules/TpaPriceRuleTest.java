package de.raindancer.modules.tpa.rules;

import de.raindancer.core.social.economy.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TpaPriceRuleTest {

    private final TpaPriceRule rule = new TpaPriceRule();
    private static final Money ZERO = Money.ZERO;

    @Test
    @DisplayName("with nothing set, a trip is free however far it goes")
    void freeByDefault() {
        assertThat(rule.trip(ZERO, ZERO, ZERO, true, 90_000)).isEqualTo(ZERO);
        assertThat(rule.trip(ZERO, ZERO, ZERO, false, Double.NaN)).isEqualTo(ZERO);
    }

    @Test
    @DisplayName("the flat price is charged for any trip")
    void flat() {
        assertThat(rule.trip(Money.of(500), ZERO, ZERO, true, 3)).isEqualTo(Money.of(500));
    }

    @Test
    @DisplayName("distance adds in proportion, within one world")
    void perHundredBlocks() {
        assertThat(rule.trip(Money.of(500), Money.of(100), ZERO, true, 250)).isEqualTo(Money.of(750));
        assertThat(rule.trip(ZERO, Money.of(100), ZERO, true, 100)).isEqualTo(Money.of(100));
    }

    @Test
    @DisplayName("a half minor unit rounds up rather than being given away")
    void rounding() {
        assertThat(rule.trip(ZERO, Money.of(3), ZERO, true, 50)).isEqualTo(Money.of(2));
    }

    @Test
    @DisplayName("across worlds the flat extra applies and distance is ignored")
    void crossWorld() {
        assertThat(rule.trip(Money.of(500), Money.of(100), Money.of(2000), false, Double.NaN))
                .isEqualTo(Money.of(2500));
        assertThat(rule.trip(Money.of(500), Money.of(100), Money.of(2000), false, 5000))
                .isEqualTo(Money.of(2500));
    }

    @Test
    @DisplayName("the cross-world extra is not charged within a world")
    void crossWorldExtraStaysHome() {
        assertThat(rule.trip(ZERO, ZERO, Money.of(2000), true, 10)).isEqualTo(ZERO);
    }

    @Test
    @DisplayName("a nonsense distance costs nothing extra instead of breaking the price")
    void nonsenseDistance() {
        assertThat(rule.trip(Money.of(500), Money.of(100), ZERO, true, -40)).isEqualTo(Money.of(500));
        assertThat(rule.trip(Money.of(500), Money.of(100), ZERO, true, Double.NaN)).isEqualTo(Money.of(500));
    }
}
