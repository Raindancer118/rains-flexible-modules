package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.EnchantLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EnchantValueRuleTest {

    private final EnchantValueRule rule = new EnchantValueRule();
    private final Money perLevel = Money.of(40);

    @Test
    @DisplayName("every level adds value, treasure counts double, curses take value away")
    void bonus() {
        assertThat(rule.bonus(List.of(new EnchantLevel("sharpness", 5, false, false)), perLevel)).isEqualTo(Money.of(200));
        assertThat(rule.bonus(List.of(new EnchantLevel("mending", 1, true, false)), perLevel)).isEqualTo(Money.of(80));
        assertThat(rule.bonus(List.of(new EnchantLevel("sharpness", 5, false, false),
                new EnchantLevel("vanishing_curse", 1, true, true)), perLevel)).isEqualTo(Money.of(120));
        assertThat(rule.bonus(List.of(), perLevel)).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("an enchanted item sells for more than its plain self, a worn one for less, never below nothing")
    void sellValue() {
        Money plain = Money.of(400);
        assertThat(rule.sellValue(plain, 1.0, Money.ZERO, 0.4)).isEqualTo(Money.of(400));
        assertThat(rule.sellValue(plain, 1.0, Money.of(200), 0.4)).isEqualTo(Money.of(480));
        assertThat(rule.sellValue(plain, 0.25, Money.of(200), 0.4)).isEqualTo(Money.of(180));
        assertThat(rule.sellValue(plain, 0.5, Money.of(-500), 0.4)).isEqualTo(Money.ZERO);
        assertThat(rule.sellValue(plain, Double.NaN, Money.ZERO, 0.4)).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("durability left is a fraction of the most, and an item that does not wear is whole")
    void durability() {
        assertThat(rule.durabilityLeft(0, 0)).isEqualTo(1.0);
        assertThat(rule.durabilityLeft(1561, 0)).isEqualTo(1.0);
        assertThat(rule.durabilityLeft(100, 75)).isEqualTo(0.25);
        assertThat(rule.durabilityLeft(100, 500)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("a book bought from the shop costs the book plus its levels, treasure double — always more than it sells for")
    void buying() {
        EnchantValueRule rule = new EnchantValueRule();
        Money book = Money.of(30);
        EnchantLevel sharpness = new EnchantLevel("sharpness", 5, false, false);
        EnchantLevel mending = new EnchantLevel("mending", 1, true, false);
        assertThat(rule.buyPrice(book, sharpness, Money.of(500), Money.of(40))).isEqualTo(Money.of(30 + 2_500));
        assertThat(rule.buyPrice(book, mending, Money.of(500), Money.of(40))).isEqualTo(Money.of(30 + 1_000));
        assertThat(rule.buyPrice(book, sharpness, Money.of(1), Money.of(40))).as("never under what it sells for")
                .isEqualTo(Money.of(30 + 200));
        Money sells = rule.sellValue(Money.of(12), 1.0, rule.bonus(java.util.List.of(sharpness), Money.of(40)), 0.4);
        assertThat(rule.buyPrice(book, sharpness, Money.of(1), Money.of(40))).isGreaterThan(sells);
    }

    @Test
    @DisplayName("what the shop offers: no curses, treasure only when allowed, nothing an owner closed")
    void offered() {
        EnchantValueRule rule = new EnchantValueRule();
        assertThat(rule.offered(new EnchantLevel("sharpness", 1, false, false), false, java.util.List.of())).isTrue();
        assertThat(rule.offered(new EnchantLevel("binding_curse", 1, true, true), true, java.util.List.of())).isFalse();
        assertThat(rule.offered(new EnchantLevel("mending", 1, true, false), false, java.util.List.of())).isFalse();
        assertThat(rule.offered(new EnchantLevel("mending", 1, true, false), true, java.util.List.of())).isTrue();
        assertThat(rule.offered(new EnchantLevel("sharpness", 1, false, false), true, java.util.List.of("Sharpness")))
                .as("closed, whatever the case").isFalse();
    }
}
