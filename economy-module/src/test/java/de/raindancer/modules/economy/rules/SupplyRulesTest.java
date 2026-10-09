package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.social.economy.MoneySupply;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SupplyRulesTest {

    private static final Currency COINS = Currency.DEFAULT;

    @Nested
    class Brackets {

        private final BracketRule rule = new BracketRule();

        @Test
        @DisplayName("each slice is taxed at its own rate, like an income tax, and rounded down")
        void marginal() {
            var brackets = rule.parse(List.of("0 1", "1000 3", "10000 8"), COINS).orElseThrow();
            // 1000 at 1% = 10, 9000 at 3% = 270, 5000 at 8% = 400
            assertThat(rule.tax(COINS.ofMajor(15_000), brackets)).isEqualTo(COINS.ofMajor(680));
            assertThat(rule.tax(COINS.ofMajor(500), brackets)).isEqualTo(COINS.ofMajor(5));
            assertThat(rule.tax(Money.of(99), brackets)).as("0.99 at 1% is under a cent").isEqualTo(Money.ZERO);
        }

        @Test
        @DisplayName("the slices may be written in any order; below the first, nothing is due")
        void unordered() {
            var brackets = rule.parse(List.of("1000 10", "500 5"), COINS).orElseThrow();
            assertThat(rule.tax(COINS.ofMajor(400), brackets)).isEqualTo(Money.ZERO);
            assertThat(rule.tax(COINS.ofMajor(1_100), brackets)).isEqualTo(COINS.ofMajor(35));
        }

        @Test
        @DisplayName("an empty list is no brackets; a line that cannot be read refuses the whole list")
        void reading() {
            assertThat(rule.parse(List.of(), COINS)).contains(List.of());
            assertThat(rule.parse(List.of("0 1", "lots 5"), COINS)).isEmpty();
            assertThat(rule.parse(List.of("0 -1"), COINS)).isEmpty();
            assertThat(rule.parse(List.of("0 101"), COINS)).as("a tax over 100% takes more than there is").isEmpty();
        }

        @Test
        @DisplayName("season points: the richer, the fewer points each further coin is worth")
        void points() {
            var scale = rule.parseRates(List.of("0 1", "10000 0.5", "100000 0.1"), COINS).orElseThrow();
            assertThat(rule.points(COINS.ofMajor(5_000), scale, COINS)).isEqualTo(5_000);
            // 10000 + 90000*0.5 + 100000*0.1
            assertThat(rule.points(COINS.ofMajor(200_000), scale, COINS)).isEqualTo(65_000);
        }
    }

    @Nested
    class Supply {

        private final SupplyRule rule = new SupplyRule();

        @Test
        @DisplayName("with nothing set, payouts are untouched")
        void untouched() {
            assertThat(rule.faucetChange(MoneySupply.open(Money.of(1_000_000), 10), 0, Money.ZERO)).isZero();
            assertThat(rule.faucetChange(MoneySupply.capped(Money.of(100), Money.of(99), 1), 0, Money.ZERO)).isZero();
        }

        @Test
        @DisplayName("below the threshold, payouts shrink in proportion to what the treasury still holds")
        void lowTreasury() {
            // threshold 20%, treasury at 10% -> half
            assertThat(rule.faucetChange(MoneySupply.capped(Money.of(1_000), Money.of(900), 1), 20, Money.ZERO))
                    .isEqualTo(-50);
            assertThat(rule.faucetChange(MoneySupply.capped(Money.of(1_000), Money.of(500), 1), 20, Money.ZERO))
                    .as("above the threshold, nothing changes").isZero();
            assertThat(rule.faucetChange(MoneySupply.capped(Money.of(1_000), Money.of(1_000), 1), 20, Money.ZERO))
                    .isEqualTo(-100);
        }

        @Test
        @DisplayName("when everybody is twice as rich as the target, payouts are half")
        void perPlayer() {
            assertThat(rule.faucetChange(MoneySupply.open(Money.of(20_000), 10), 0, Money.of(1_000)))
                    .isEqualTo(-50);
            assertThat(rule.faucetChange(MoneySupply.open(Money.of(5_000), 10), 0, Money.of(1_000))).isZero();
        }

        @Test
        @DisplayName("both brakes multiply")
        void both() {
            assertThat(rule.faucetChange(MoneySupply.capped(Money.of(10_000), Money.of(9_500), 2), 10,
                    Money.of(2_375))).isEqualTo(-75);
        }
    }

    @Nested
    class Stabilizer {

        private final StabilizerRule rule = new StabilizerRule();

        @Test
        @DisplayName("prices rising faster than the target turn payouts down and fees up, one step a day")
        void inflation() {
            assertThat(rule.next(new StabilizerRule.Taps(0, 0), 5.0, 1.0, 0.5, 5, 50))
                    .isEqualTo(new StabilizerRule.Taps(-5, 5));
        }

        @Test
        @DisplayName("prices falling turn them back the other way, and past neutral if they keep falling")
        void deflation() {
            assertThat(rule.next(new StabilizerRule.Taps(-5, 5), -2.0, 1.0, 0.5, 5, 50))
                    .isEqualTo(new StabilizerRule.Taps(0, 0));
            assertThat(rule.next(new StabilizerRule.Taps(0, 0), -2.0, 1.0, 0.5, 5, 50))
                    .isEqualTo(new StabilizerRule.Taps(5, -5));
        }

        @Test
        @DisplayName("within the tolerance nothing moves, and the taps never pass the limit")
        void bounds() {
            assertThat(rule.next(new StabilizerRule.Taps(-10, 10), 1.3, 1.0, 0.5, 5, 50))
                    .isEqualTo(new StabilizerRule.Taps(-10, 10));
            assertThat(rule.next(new StabilizerRule.Taps(-48, 48), 9.0, 1.0, 0.5, 5, 50))
                    .isEqualTo(new StabilizerRule.Taps(-50, 50));
        }

        @Test
        @DisplayName("weekly inflation is read from the basket's price a week apart")
        void weekly() {
            assertThat(rule.weeklyPercent(Map.of(10L, 1000L, 17L, 1100L), 17))
                    .hasValueCloseTo(10.0, org.assertj.core.data.Offset.offset(0.0001));
            assertThat(rule.weeklyPercent(Map.of(14L, 1000L, 17L, 1030L), 17))
                    .as("fewer days are stretched to a week").hasValueCloseTo(7.0, org.assertj.core.data.Offset.offset(0.01));
            assertThat(rule.weeklyPercent(Map.of(17L, 1000L), 17)).as("one day says nothing").isEmpty();
        }
    }

    @Test
    @DisplayName("the same mob in the same chunk pays less each time; a fresh one pays in full")
    void diminishing() {
        DiminishingRule rule = new DiminishingRule();
        assertThat(rule.payout(Money.of(1_000), 0, 20)).isEqualTo(Money.of(1_000));
        assertThat(rule.payout(Money.of(1_000), 1, 20)).isEqualTo(Money.of(800));
        assertThat(rule.payout(Money.of(1_000), 2, 20)).isEqualTo(Money.of(640));
        assertThat(rule.payout(Money.of(1_000), 5, 0)).as("off").isEqualTo(Money.of(1_000));
        assertThat(rule.payout(Money.of(1_000), 500, 50)).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("a repair costs a share of the item's worth, scaled by how worn it is, never under the least")
    void repair() {
        RepairRule rule = new RepairRule();
        assertThat(rule.price(Optional.of(Money.of(10_000)), 50, 100, 10.0, Money.of(100)))
                .contains(Money.of(500));
        assertThat(rule.price(Optional.of(Money.of(10_000)), 1, 1000, 10.0, Money.of(100)))
                .contains(Money.of(100));
        assertThat(rule.price(Optional.empty(), 50, 100, 10.0, Money.of(100)))
                .as("an item the shop does not price costs the least").contains(Money.of(100));
        assertThat(rule.price(Optional.of(Money.of(10_000)), 0, 100, 10.0, Money.of(100)))
                .as("nothing to repair").isEmpty();
    }

    @Test
    @DisplayName("dying costs a share of the balance, rounded down, never past the most")
    void death() {
        DeathRule rule = new DeathRule();
        assertThat(rule.loss(Money.of(10_000), 5.0, Money.ZERO)).isEqualTo(Money.of(500));
        assertThat(rule.loss(Money.of(10_000), 5.0, Money.of(200))).isEqualTo(Money.of(200));
        assertThat(rule.loss(Money.of(10_000), 0.0, Money.ZERO)).isEqualTo(Money.ZERO);
        assertThat(rule.loss(Money.of(-5), 5.0, Money.ZERO)).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("a banknote past its expiry pays only its share; coins and fresh notes pay in full")
    void expiry() {
        NoteExpiryRule rule = new NoteExpiryRule();
        long day = 86_400_000L;
        assertThat(rule.worth(Money.of(1_000), 0, 31 * day, 30, 50)).isEqualTo(Money.of(500));
        assertThat(rule.worth(Money.of(1_000), 0, 29 * day, 30, 50)).isEqualTo(Money.of(1_000));
        assertThat(rule.worth(Money.of(1_000), 0, 999 * day, 0, 50)).as("never expires").isEqualTo(Money.of(1_000));
        assertThat(rule.worth(Money.of(1_001), 0, 31 * day, 30, 50)).isEqualTo(Money.of(500));
    }

    @Test
    @DisplayName("the basket is read as items and counts; the index is today's price over the first one recorded")
    void basket() {
        PriceIndexRule rule = new PriceIndexRule();
        assertThat(rule.basket(List.of("bread 16", "diamond 2", "nonsense", "iron_ingot x")))
                .containsExactly(Map.entry("BREAD", 16), Map.entry("DIAMOND", 2));
        assertThat(rule.level(Map.of(3L, 2_000L, 1L, 1_000L, 2L, 1_500L), 3)).isEqualTo(2.0);
        assertThat(rule.level(Map.of(), 3)).isEqualTo(1.0);
        assertThat(rule.level(Map.of(1L, 0L, 3L, 500L), 3)).as("a basket nobody priced is no base").isEqualTo(1.0);
    }
}
