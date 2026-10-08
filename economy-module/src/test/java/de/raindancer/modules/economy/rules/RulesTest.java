package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.EconomyResult.Outcome;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.DailyClaim;
import de.raindancer.modules.economy.model.Denomination;
import de.raindancer.modules.economy.model.Form;
import de.raindancer.modules.economy.model.PaymentRefusal;
import de.raindancer.modules.economy.model.RecipeShape;
import de.raindancer.modules.economy.model.Split;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RulesTest {

    private static Money m(long minor) {
        return Money.of(minor);
    }

    @Nested
    @DisplayName("a balance")
    class Balances {
        private final BalanceRule rule = new BalanceRule();

        @Test
        @DisplayName("never goes below zero, never past the most, and a frozen one does not move at all")
        void limits() {
            assertThat(rule.apply(m(500), m(-500), m(10_000), false).after()).isEqualTo(Money.ZERO);
            assertThat(rule.apply(m(500), m(-501), m(10_000), false).outcome()).isEqualTo(Outcome.NOT_ENOUGH);
            assertThat(rule.apply(m(9_000), m(1_001), m(10_000), false).outcome()).isEqualTo(Outcome.TOO_MUCH);
            assertThat(rule.apply(m(9_000), m(1_000), m(10_000), false).after()).isEqualTo(m(10_000));
            assertThat(rule.apply(m(500), m(1), m(10_000), true).outcome()).isEqualTo(Outcome.FROZEN);
            assertThat(rule.apply(m(500), Money.ZERO, m(10_000), false).outcome()).isEqualTo(Outcome.INVALID_AMOUNT);
        }

        @Test
        @DisplayName("an overflow is too much, not a wrapped negative fortune")
        void overflow() {
            assertThat(rule.apply(m(Long.MAX_VALUE), m(1), m(Long.MAX_VALUE), false).outcome())
                    .isEqualTo(Outcome.TOO_MUCH);
        }

        @Test
        @DisplayName("a balance already above a lowered maximum may still be spent from")
        void loweredMaximum() {
            assertThat(rule.apply(m(20_000), m(-100), m(10_000), false).allowed()).isTrue();
            assertThat(rule.apply(m(20_000), m(100), m(10_000), false).allowed()).isFalse();
        }
    }

    @Nested
    @DisplayName("a payment")
    class Payments {
        private final PaymentRule rule = new PaymentRule();
        private final UUID a = UUID.randomUUID();
        private final UUID b = UUID.randomUUID();

        @Test
        @DisplayName("is refused to yourself, for nothing, and below the minimum")
        void refusals() {
            assertThat(rule.refusal(a, a, m(100), m(1))).contains(PaymentRefusal.TO_YOURSELF);
            assertThat(rule.refusal(a, b, Money.ZERO, m(1))).contains(PaymentRefusal.NOT_POSITIVE);
            assertThat(rule.refusal(a, b, m(5), m(10))).contains(PaymentRefusal.BELOW_MINIMUM);
            assertThat(rule.refusal(a, b, m(10), m(10))).isEmpty();
        }

        @Test
        @DisplayName("tax is taken from what arrives, rounded down, and never all of it")
        void tax() {
            assertThat(rule.tax(m(1000), 0.05)).isEqualTo(m(50));
            assertThat(rule.tax(m(19), 0.05)).isEqualTo(Money.ZERO);
            assertThat(rule.tax(m(1000), 0)).isEqualTo(Money.ZERO);
            assertThat(rule.tax(m(1000), 0.9)).isEqualTo(m(500));
        }

        @Test
        @DisplayName("needs confirming only above the threshold, and never when the threshold is zero")
        void confirming() {
            assertThat(rule.needsConfirming(m(1001), m(1000))).isTrue();
            assertThat(rule.needsConfirming(m(1000), m(1000))).isFalse();
            assertThat(rule.needsConfirming(m(999_999), Money.ZERO)).isFalse();
        }
    }

    @Nested
    @DisplayName("making change")
    class Change {
        private final ChangeRule rule = new ChangeRule();
        private final Denomination one = new Denomination(m(100), Material.GOLD_NUGGET, Form.COIN);
        private final Denomination ten = new Denomination(m(1000), Material.GOLD_INGOT, Form.COIN);
        private final Denomination hundred = new Denomination(m(10_000), Material.PAPER, Form.NOTE);

        @Test
        @DisplayName("uses the largest pieces first and leaves what no piece can make in the account")
        void greedy() {
            Split split = rule.split(m(12_350), List.of(hundred, ten, one));
            assertThat(split.pieces()).containsEntry(hundred, 1).containsEntry(ten, 2).containsEntry(one, 3);
            assertThat(split.leftover()).isEqualTo(m(50));
            assertThat(split.paidOut()).isEqualTo(m(12_300));
            assertThat(split.count()).isEqualTo(6);
        }

        @Test
        @DisplayName("accepts the denominations in any order")
        void order() {
            assertThat(rule.split(m(1_100), List.of(one, ten)).count()).isEqualTo(2);
        }

        @Test
        @DisplayName("with nothing to make it from, everything is left over")
        void nothing() {
            assertThat(rule.split(m(500), List.of()).leftover()).isEqualTo(m(500));
            assertThat(rule.split(m(50), List.of(one)).count()).isZero();
        }
    }

    @Nested
    @DisplayName("prices worked out from recipes")
    class Solving {
        private final PriceSolverRule rule = new PriceSolverRule();

        private RecipeShape craft(String result, int amount, String... inputs) {
            return new RecipeShape(result, amount, java.util.Arrays.stream(inputs).map(List::of).toList(),
                    RecipeShape.Process.CRAFT);
        }

        @Test
        @DisplayName("a crafted item costs its ingredients plus the markup, per item made")
        void simple() {
            Map<String, Money> solved = rule.solve(Map.of("OAK_LOG", m(200)),
                    List.of(craft("OAK_PLANKS", 4, "OAK_LOG")), 0.10, 0.15);
            assertThat(solved).containsEntry("OAK_PLANKS", m(55));
            assertThat(solved).containsEntry("OAK_LOG", m(200));
        }

        @Test
        @DisplayName("chains resolve whatever order the recipes come in")
        void chains() {
            Map<String, Money> solved = rule.solve(Map.of("OAK_LOG", m(400)), List.of(
                    craft("STICK", 4, "OAK_PLANKS", "OAK_PLANKS"),
                    craft("OAK_PLANKS", 4, "OAK_LOG")), 0.0, 0.0);
            assertThat(solved).containsEntry("OAK_PLANKS", m(100)).containsEntry("STICK", m(50));
        }

        @Test
        @DisplayName("the cheapest of several recipes wins, and a raw price is never overwritten")
        void cheapest() {
            Map<String, Money> solved = rule.solve(Map.of("A", m(100), "B", m(1000), "C", m(5)), List.of(
                    craft("X", 1, "B"), craft("X", 1, "A"), craft("C", 1, "A")), 0.0, 0.0);
            assertThat(solved).containsEntry("X", m(100)).containsEntry("C", m(5));
        }

        @Test
        @DisplayName("a slot that takes any of several materials is priced at the cheapest it takes")
        void alternatives() {
            RecipeShape chest = new RecipeShape("CHEST", 1, List.of(List.of("OAK_PLANKS", "BIRCH_PLANKS")),
                    RecipeShape.Process.CRAFT);
            assertThat(rule.solve(Map.of("OAK_PLANKS", m(50), "BIRCH_PLANKS", m(20)), List.of(chest), 0, 0))
                    .containsEntry("CHEST", m(20));
        }

        @Test
        @DisplayName("an ingredient nobody can price leaves the result unpriced rather than cheap")
        void unknown() {
            assertThat(rule.solve(Map.of("A", m(100)), List.of(craft("X", 1, "A", "MYSTERY")), 0, 0))
                    .doesNotContainKey("X");
        }

        @Test
        @DisplayName("a storage block and its ingots do not feed each other into a spiral")
        void cycles() {
            Map<String, Money> solved = rule.solve(Map.of("RAW_IRON", m(800)), List.of(
                    new RecipeShape("IRON_INGOT", 1, List.of(List.of("RAW_IRON")), RecipeShape.Process.SMELT),
                    craft("IRON_BLOCK", 1, "IRON_INGOT", "IRON_INGOT", "IRON_INGOT", "IRON_INGOT", "IRON_INGOT",
                            "IRON_INGOT", "IRON_INGOT", "IRON_INGOT", "IRON_INGOT"),
                    craft("IRON_INGOT", 9, "IRON_BLOCK")), 0.10, 0.25);
            assertThat(solved).containsEntry("IRON_INGOT", m(1000)).containsEntry("IRON_BLOCK", m(9900));
        }

        @Test
        @DisplayName("nothing is ever priced at zero")
        void neverFree() {
            assertThat(rule.solve(Map.of("A", m(1)), List.of(craft("B", 64, "A")), 0, 0)).containsEntry("B", m(1));
        }
    }

    @Nested
    @DisplayName("supply and demand")
    class Market {
        private final MarketRule rule = new MarketRule();

        @Test
        @DisplayName("buying pushes up, selling pushes down, and either wears off by half in a half-life")
        void pressure() {
            double bought = rule.pushed(0, 64, 64, 0.02, true);
            assertThat(bought).isEqualTo(0.02);
            assertThat(rule.pushed(bought, 32, 64, 0.02, false)).isEqualTo(0.01, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(rule.decayed(0.4, 3_600_000L * 6, 6)).isEqualTo(0.2, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(rule.decayed(0.4, -5, 6)).isEqualTo(0.4);
        }

        @Test
        @DisplayName("the price moves smoothly and never past the swing")
        void multiplier() {
            assertThat(rule.multiplier(0, 0.5)).isEqualTo(1.0);
            assertThat(rule.multiplier(1000, 0.5)).isLessThanOrEqualTo(1.5).isGreaterThan(1.49);
            assertThat(rule.multiplier(-1000, 0.5)).isGreaterThanOrEqualTo(0.5).isLessThan(0.51);
            assertThat(rule.multiplier(0.1, 0.5)).isGreaterThan(1.0);
        }
    }

    @Nested
    @DisplayName("trade prices")
    class Trade {
        private final TradePriceRule rule = new TradePriceRule();

        @Test
        @DisplayName("buying rounds up and selling rounds down, so neither ever mints a cent")
        void rounding() {
            assertThat(rule.unitBuy(m(100), 1.0, 1.005)).isEqualTo(m(101));
            assertThat(rule.unitSell(m(100), 1.0, 0.405)).isEqualTo(m(40));
            assertThat(rule.unitBuy(m(1), 1.0, 0.01)).as("never free").isEqualTo(m(1));
        }

        @Test
        @DisplayName("a custom sell price is still kept below what buying costs")
        void custom() {
            assertThat(rule.capSellBelowBuy(m(500), m(400))).isEqualTo(m(399));
            assertThat(rule.capSellBelowBuy(m(300), m(400))).isEqualTo(m(300));
            assertThat(rule.capSellBelowBuy(m(5), m(1))).isEqualTo(Money.ZERO);
        }

        @Test
        @DisplayName("a total is the unit times the quantity, and an overflow is refused")
        void totals() {
            assertThat(rule.total(m(150), 64)).contains(m(9600));
            assertThat(rule.total(m(Long.MAX_VALUE), 2)).isEqualTo(Optional.empty());
        }
    }

    @Nested
    @DisplayName("the daily reward")
    class Daily {
        private final DailyRule rule = new DailyRule();

        @Test
        @DisplayName("is once a day, counts a streak of consecutive days, and a gap starts again")
        void streaks() {
            assertThat(rule.claim(-1, 0, 100, 7)).isEqualTo(new DailyClaim(true, 1, 100, 0));
            assertThat(rule.claim(100, 1, 100, 7).allowed()).isFalse();
            assertThat(rule.claim(100, 1, 101, 7).streak()).isEqualTo(2);
            assertThat(rule.claim(100, 5, 103, 7).streak()).isEqualTo(1);
            assertThat(rule.claim(100, 7, 101, 7).streak()).as("held at the most").isEqualTo(7);
        }

        @Test
        @DisplayName("pays the base plus the bonus for every day after the first")
        void amount() {
            assertThat(rule.amount(m(5000), m(1000), 1)).isEqualTo(m(5000));
            assertThat(rule.amount(m(5000), m(1000), 4)).isEqualTo(m(8000));
        }
    }

    @Nested
    @DisplayName("interest")
    class Interest {
        private final InterestRule rule = new InterestRule();

        @Test
        @DisplayName("is a share of the balance, capped, and never past the account's maximum")
        void interest() {
            assertThat(rule.interest(m(100_000), 0.01, m(5_000), m(10_000_000))).isEqualTo(m(1000));
            assertThat(rule.interest(m(10_000_000), 0.01, m(5_000), m(100_000_000))).isEqualTo(m(5000));
            assertThat(rule.interest(m(9_999_900), 0.01, Money.ZERO, m(10_000_000))).isEqualTo(m(100));
            assertThat(rule.interest(Money.ZERO, 0.01, m(5000), m(10_000))).isEqualTo(Money.ZERO);
            assertThat(rule.interest(m(-100), 0.01, m(5000), m(10_000))).isEqualTo(Money.ZERO);
        }
    }

    @Nested
    @DisplayName("the hourly earning cap")
    class Cap {
        private final EarningCapRule rule = new EarningCapRule();

        @Test
        @DisplayName("pays in full below the cap, pays the rest up to it, then nothing")
        void cap() {
            assertThat(rule.allowed(m(0), m(1000), m(300))).isEqualTo(m(300));
            assertThat(rule.allowed(m(900), m(1000), m(300))).isEqualTo(m(100));
            assertThat(rule.allowed(m(1000), m(1000), m(300))).isEqualTo(Money.ZERO);
            assertThat(rule.allowed(m(99_999), Money.ZERO, m(300))).as("no cap").isEqualTo(m(300));
        }
    }

    @Nested
    @DisplayName("being away")
    class Away {
        private final ActivityRule rule = new ActivityRule();

        @Test
        @DisplayName("somebody is active until they have not moved for the set minutes")
        void afk() {
            assertThat(rule.active(0, 4 * 60_000L, 5)).isTrue();
            assertThat(rule.active(0, 5 * 60_000L, 5)).isFalse();
        }
    }
}
