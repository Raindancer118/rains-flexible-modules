package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.EnchantKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EnchantValueRuleTest {

    private final EnchantValueRule rule = new EnchantValueRule();

    @Test
    @DisplayName("an enchantment's part of a book's price is what the book sells for more than a plain one")
    void part() {
        assertThat(rule.part(Money.of(1000), Money.of(28))).isEqualTo(Money.of(972));
        assertThat(rule.part(Money.of(10), Money.of(28))).as("never less than nothing").isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("an enchanted item sells for its plain self, worn down, plus its enchantments' parts, minus its curses'")
    void itemSell() {
        Money plain = Money.of(400);
        assertThat(rule.itemSell(plain, 1.0, Money.ZERO, Money.ZERO)).isEqualTo(Money.of(400));
        assertThat(rule.itemSell(plain, 1.0, Money.of(200), Money.ZERO)).isEqualTo(Money.of(600));
        assertThat(rule.itemSell(plain, 0.25, Money.of(200), Money.ZERO)).isEqualTo(Money.of(300));
        assertThat(rule.itemSell(plain, 1.0, Money.of(200), Money.of(50))).isEqualTo(Money.of(550));
        assertThat(rule.itemSell(plain, 0.5, Money.ZERO, Money.of(5000))).isEqualTo(Money.ZERO);
        assertThat(rule.itemSell(plain, Double.NaN, Money.ZERO, Money.ZERO)).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("a book of several enchantments sells as the books of each, less the books that went into combining them")
    void bookSell() {
        Money book = Money.of(28);
        assertThat(rule.bookSell(book, List.of(Money.of(500)), Money.ZERO)).isEqualTo(Money.of(500));
        assertThat(rule.bookSell(book, List.of(Money.of(500), Money.of(300)), Money.ZERO)).isEqualTo(Money.of(772));
        assertThat(rule.bookSell(book, List.of(Money.of(500)), Money.of(100))).isEqualTo(Money.of(400));
        assertThat(rule.bookSell(book, List.of(), Money.of(100))).isEqualTo(Money.ZERO);
        assertThat(rule.bookSell(book, List.of(), Money.ZERO)).isEqualTo(book);
    }

    @Test
    @DisplayName("a curse takes away its part at the sell ratio")
    void curse() {
        assertThat(rule.cursePenalty(Money.of(1000), 0.4)).isEqualTo(Money.of(400));
        assertThat(rule.cursePenalty(Money.of(1000), -1)).isEqualTo(Money.ZERO);
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
    @DisplayName("what the shop offers: no curses, treasure only when allowed, nothing an owner closed")
    void offered() {
        EnchantKind sharpness = new EnchantKind("sharpness", 5, false, false);
        EnchantKind mending = new EnchantKind("mending", 1, true, false);
        assertThat(rule.offered(sharpness, false, List.of())).isTrue();
        assertThat(rule.offered(new EnchantKind("binding_curse", 1, true, true), true, List.of())).isFalse();
        assertThat(rule.offered(mending, false, List.of())).isFalse();
        assertThat(rule.offered(mending, true, List.of())).isTrue();
        assertThat(rule.offered(sharpness, true, List.of("Sharpness"))).as("closed, whatever the case").isFalse();
        assertThat(rule.offered(sharpness, true, List.of("minecraft:sharpness"))).isFalse();
    }

    @Test
    @DisplayName("bought, stripped, the bare book sold: never more than the book cost, with buying above the plain book and selling under buying")
    void noStripLoop() {
        java.util.Random random = new java.util.Random(11);
        for (int i = 0; i < 5000; i++) {
            long plainBook = random.nextInt(200);
            // As EnchantTradeRule keeps them: buying above the plain book's sell price, selling under buying.
            long buy = plainBook + 1 + random.nextInt(100_000);
            long sell = random.nextInt((int) buy);
            Money paid = rule.part(Money.of(sell), Money.of(plainBook));
            assertThat(paid.minor() + plainBook).as("strip, then sell the plain book left").isLessThan(buy);
            Money whole = rule.bookSell(Money.of(plainBook), List.of(Money.of(sell), Money.of(sell)), Money.ZERO);
            assertThat(whole.minor()).as("two bought books combined into one, sold").isLessThan(2 * buy);
        }
    }

    @Test
    @DisplayName("only real levels count: a level below I adds nothing, one above the maximum counts as the maximum")
    void levels() {
        assertThat(rule.level(0, 5)).isEmpty();
        assertThat(rule.level(-3, 5)).isEmpty();
        assertThat(rule.level(3, 5)).hasValue(3);
        assertThat(rule.level(255, 5)).hasValue(5);
    }

    @Test
    @DisplayName("something that does not stack is valued once: a stack of them is not an item the shop knows")
    void stacks() {
        assertThat(rule.countable(1, 1)).isTrue();
        assertThat(rule.countable(2, 1)).isFalse();
        assertThat(rule.countable(64, 64)).isTrue();
    }
}
