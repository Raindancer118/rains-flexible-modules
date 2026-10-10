package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.EnchantLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EnchantValueRuleTest {

    private final EnchantValueRule rule = new EnchantValueRule();
    private final EnchantWorthRule worth = new EnchantWorthRule(List.of());
    private final Money perLevel = Money.of(40);

    @Test
    @DisplayName("an enchantment adds what it is worth, the useful ones more; curses take value away")
    void bonus() {
        EnchantLevel sharpness = new EnchantLevel("sharpness", 5, 5, false, false);
        EnchantLevel bane = new EnchantLevel("bane_of_arthropods", 5, 5, false, false);
        assertThat(rule.bonus(List.of(sharpness), perLevel, worth))
                .isEqualTo(Money.of(Math.round(40 * worth.worth(sharpness))));
        assertThat(rule.bonus(List.of(sharpness), perLevel, worth)).isGreaterThan(rule.bonus(List.of(bane), perLevel, worth));
        assertThat(rule.bonus(List.of(sharpness, new EnchantLevel("vanishing_curse", 1, 1, true, true)), perLevel, worth))
                .isLessThan(rule.bonus(List.of(sharpness), perLevel, worth));
        assertThat(rule.bonus(List.of(), perLevel, worth)).isEqualTo(Money.ZERO);
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
    @DisplayName("a book bought from the shop costs the book plus what the enchantment is worth — always more than it sells for")
    void buying() {
        EnchantValueRule rule = new EnchantValueRule();
        Money book = Money.of(30);
        EnchantLevel sharpness = new EnchantLevel("sharpness", 5, 5, false, false);
        EnchantLevel mending = new EnchantLevel("mending", 1, 1, true, false);
        EnchantLevel bane = new EnchantLevel("bane_of_arthropods", 5, 5, false, false);
        assertThat(rule.buyPrice(book, sharpness, Money.of(500), Money.of(40), worth))
                .isEqualTo(Money.of(30 + Math.round(500 * worth.worth(sharpness))));
        assertThat(rule.buyPrice(book, mending, Money.of(500), Money.of(40), worth))
                .as("Mending, one level, the most useful of all").isGreaterThan(rule.buyPrice(book, sharpness, Money.of(500), Money.of(40), worth));
        assertThat(rule.buyPrice(book, bane, Money.of(500), Money.of(40), worth))
                .isLessThan(rule.buyPrice(book, sharpness, Money.of(500), Money.of(40), worth));
        assertThat(rule.buyPrice(book, sharpness, Money.of(1), Money.of(40), worth)).as("never under what it sells for")
                .isEqualTo(Money.of(30 + Math.round(40 * worth.worth(sharpness))));
        Money sells = rule.sellValue(Money.of(12), 1.0, rule.bonus(java.util.List.of(sharpness), Money.of(40), worth), 0.4);
        assertThat(rule.buyPrice(book, sharpness, Money.of(1), Money.of(40), worth)).isGreaterThan(sells);
    }

    @Test
    @DisplayName("what the shop offers: no curses, treasure only when allowed, nothing an owner closed")
    void offered() {
        EnchantValueRule rule = new EnchantValueRule();
        assertThat(rule.offered(new EnchantLevel("sharpness", 1, 5, false, false), false, java.util.List.of())).isTrue();
        assertThat(rule.offered(new EnchantLevel("binding_curse", 1, 1, true, true), true, java.util.List.of())).isFalse();
        assertThat(rule.offered(new EnchantLevel("mending", 1, 1, true, false), false, java.util.List.of())).isFalse();
        assertThat(rule.offered(new EnchantLevel("mending", 1, 1, true, false), true, java.util.List.of())).isTrue();
        assertThat(rule.offered(new EnchantLevel("sharpness", 1, 5, false, false), true, java.util.List.of("Sharpness")))
                .as("closed, whatever the case").isFalse();
    }

    @Test
    @DisplayName("enchantments sold off an item pay what selling them on it would add; curses stay and cost nothing")
    void enchantsOff() {
        EnchantLevel sharpness = new EnchantLevel("sharpness", 5, 5, false, false);
        EnchantLevel unbreaking = new EnchantLevel("unbreaking", 3, 3, false, false);
        EnchantLevel vanishing = new EnchantLevel("vanishing_curse", 1, 1, true, true);
        Money onTheItem = rule.sellValue(Money.ZERO, 1.0, rule.bonus(List.of(sharpness, unbreaking), perLevel, worth), 0.4);
        assertThat(rule.enchantsOff(List.of(sharpness, unbreaking), perLevel, worth, 0.4)).isEqualTo(onTheItem);
        assertThat(rule.enchantsOff(List.of(sharpness, unbreaking, vanishing), perLevel, worth, 0.4)).isEqualTo(onTheItem);
        assertThat(rule.enchantsOff(List.of(vanishing), perLevel, worth, 0.4)).isEqualTo(Money.ZERO);
        assertThat(rule.enchantsOff(List.of(sharpness), perLevel, worth, 0.4))
                .as("never more than a book of it costs").isLessThan(rule.buyPrice(Money.ZERO, sharpness, Money.of(1), perLevel, worth));
    }
}
