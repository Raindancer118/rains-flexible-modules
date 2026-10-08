package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.BetRefusal;
import de.raindancer.modules.economy.model.SlotSymbol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The house always wins — by exactly the edge it says, and not a cent more. */
class GamblingRulesTest {

    private final GambleRule gamble = new GambleRule();
    private final SlotsRule slots = new SlotsRule();
    private final LotteryRule lottery = new LotteryRule();

    private static Money m(long minor) {
        return Money.of(minor);
    }

    @Test
    @DisplayName("bets outside the limits, or past the day's loss limit, are refused")
    void limits() {
        assertThat(gamble.refusal(m(50), m(100), m(1000), Money.ZERO, Money.ZERO)).contains(BetRefusal.BELOW_MINIMUM);
        assertThat(gamble.refusal(m(1001), m(100), m(1000), Money.ZERO, Money.ZERO)).contains(BetRefusal.ABOVE_MAXIMUM);
        assertThat(gamble.refusal(m(500), m(100), m(1000), m(600), m(1000))).contains(BetRefusal.LOSS_LIMIT);
        assertThat(gamble.refusal(m(400), m(100), m(1000), m(600), m(1000))).isEmpty();
        assertThat(gamble.refusal(m(500), m(100), m(1000), m(99999), Money.ZERO)).as("no limit").isEmpty();
    }

    @Test
    @DisplayName("a coin flip pays double less the edge")
    void flip() {
        assertThat(gamble.payout(m(1000), 0.5, 0.03)).isEqualTo(m(1940));
        assertThat(gamble.payout(m(1000), 0.5, 0)).isEqualTo(m(2000));
        assertThat(gamble.payout(m(1000), 0.5, 0.9)).as("edge capped at half").isEqualTo(m(1000));
    }

    @Test
    @DisplayName("dice odds follow the target, and silly targets are refused")
    void dice() {
        assertThat(gamble.diceChance(true, 50)).isEqualTo(0.5);
        assertThat(gamble.diceChance(false, 11)).isEqualTo(0.1);
        assertThat(gamble.diceWins(true, 50, 51)).isTrue();
        assertThat(gamble.diceWins(true, 50, 50)).isFalse();
        assertThat(gamble.diceWins(false, 11, 10)).isTrue();
        assertThat(gamble.diceValid(true, 99)).isTrue();
        assertThat(gamble.diceValid(true, 100)).as("cannot win").isFalse();
        assertThat(gamble.diceValid(false, 100)).as("nearly certain").isFalse();
        assertThat(gamble.diceValid(true, 0)).isFalse();
    }

    @Test
    @DisplayName("every dice bet returns exactly one minus the edge on average")
    void diceFairness() {
        for (int target = 2; target <= 98; target++) {
            for (boolean over : new boolean[]{true, false}) {
                if (!gamble.diceValid(over, target)) {
                    continue;
                }
                double chance = gamble.diceChance(over, target);
                double expected = chance * gamble.payout(m(1_000_000), chance, 0.03).minor() / 1_000_000.0;
                assertThat(expected).isCloseTo(0.97, within(0.0001));
            }
        }
    }

    @Test
    @DisplayName("the slot machine returns exactly one minus the edge, counted over every combination")
    void slotsReturn() {
        double total = Math.pow(SlotSymbol.totalWeight(), 3);
        double expected = 0;
        for (SlotSymbol a : SlotSymbol.values()) {
            for (SlotSymbol b : SlotSymbol.values()) {
                for (SlotSymbol c : SlotSymbol.values()) {
                    expected += a.weight() * b.weight() * c.weight() / total * slots.multiplier(List.of(a, b, c), 0.03);
                }
            }
        }
        assertThat(expected).isCloseTo(0.97, within(1e-9));
        assertThat(SlotsRule.baseReturn()).isBetween(0.5, 1.5);
    }

    @Test
    @DisplayName("the reels land on every symbol, as often as their weight says")
    void reels() {
        RandomGenerator random = RandomGenerator.of("L64X128MixRandom");
        Map<SlotSymbol, Integer> seen = new HashMap<>();
        for (int i = 0; i < 64_000; i++) {
            slots.spin(random::nextInt).forEach(symbol -> seen.merge(symbol, 1, Integer::sum));
        }
        assertThat(seen).containsOnlyKeys(SlotSymbol.values());
        double coalShare = seen.get(SlotSymbol.COAL) / (64_000.0 * 3);
        assertThat(coalShare).isCloseTo(10.0 / SlotSymbol.totalWeight(), within(0.01));
    }

    @Test
    @DisplayName("three of a kind, two netherite and a pair all pay; nothing alike pays nothing")
    void table() {
        assertThat(SlotsRule.rawMultiplier(List.of(SlotSymbol.GOLD, SlotSymbol.GOLD, SlotSymbol.GOLD))).isEqualTo(20);
        assertThat(SlotsRule.rawMultiplier(List.of(SlotSymbol.NETHERITE, SlotSymbol.COAL, SlotSymbol.NETHERITE))).isEqualTo(25);
        assertThat(SlotsRule.rawMultiplier(List.of(SlotSymbol.IRON, SlotSymbol.IRON, SlotSymbol.COAL))).isEqualTo(1);
        assertThat(SlotsRule.rawMultiplier(List.of(SlotSymbol.IRON, SlotSymbol.COAL, SlotSymbol.IRON))).isZero();
        assertThat(SlotsRule.rawMultiplier(List.of(SlotSymbol.COAL, SlotSymbol.IRON, SlotSymbol.GOLD))).isZero();
    }

    @Test
    @DisplayName("a pick is that many different numbers in range, and a draw is too")
    void lotteryPicks() {
        assertThat(lottery.valid(List.of(1, 7, 13, 20), 4, 20)).isTrue();
        assertThat(lottery.valid(List.of(1, 7, 7, 20), 4, 20)).as("the same number twice").isFalse();
        assertThat(lottery.valid(List.of(1, 7, 13, 21), 4, 20)).isFalse();
        assertThat(lottery.valid(List.of(1, 7, 13), 4, 20)).isFalse();
        RandomGenerator random = RandomGenerator.of("L64X128MixRandom");
        for (int i = 0; i < 100; i++) {
            assertThat(lottery.valid(lottery.draw(new java.util.Random(i), 4, 20), 4, 20)).isTrue();
        }
        assertThat(random).isNotNull();
    }

    @Test
    @DisplayName("tiers: all right, one short, two short; fewer right wins nothing")
    void lotteryTiers() {
        assertThat(lottery.matches(List.of(1, 2, 3, 4), List.of(2, 3, 4, 9))).isEqualTo(3);
        assertThat(lottery.tier(4, 4)).isZero();
        assertThat(lottery.tier(3, 4)).isEqualTo(1);
        assertThat(lottery.tier(2, 4)).isEqualTo(2);
        assertThat(lottery.tier(1, 4)).isEqualTo(-1);
        double total = 0;
        for (int right = 0; right <= 4; right++) {
            total += lottery.chance(right, 4, 20);
        }
        assertThat(total).isCloseTo(1.0, within(1e-9));
        assertThat(lottery.chance(4, 4, 20)).isCloseTo(1 / 4845.0, within(1e-12));
    }

    @Test
    @DisplayName("each tier's pool is split between its winners, and a tier nobody hit stays in the pot")
    void lotteryPrizes() {
        UUID a = new UUID(0, 1);
        UUID b = new UUID(0, 2);
        UUID c = new UUID(0, 3);
        List<Integer> drawn = List.of(1, 2, 3, 4);
        var tickets = List.of(
                new de.raindancer.modules.economy.model.LotteryTicket(a, List.of(1, 2, 3, 9)),
                new de.raindancer.modules.economy.model.LotteryTicket(b, List.of(1, 2, 3, 10)),
                new de.raindancer.modules.economy.model.LotteryTicket(c, List.of(1, 2, 11, 12)),
                new de.raindancer.modules.economy.model.LotteryTicket(c, List.of(13, 14, 15, 16)));
        Map<UUID, Money> prizes = lottery.prizes(tickets, drawn, m(10_000), 4);
        assertThat(prizes).containsEntry(a, m(1_250)).containsEntry(b, m(1_250)).containsEntry(c, m(1_500));
        long paid = prizes.values().stream().mapToLong(Money::minor).sum();
        assertThat(10_000 - paid).as("the jackpot pool rolls over").isEqualTo(6_000);
        assertThat(lottery.afterCut(m(100), 0.1)).isEqualTo(m(90));
        assertThat(lottery.allowed(95, 10, 100)).isEqualTo(5);
    }

    @Test
    @DisplayName("a lottery ticket limit of zero is no limit")
    void lotteryNoLimit() {
        LotteryRule lottery = new LotteryRule();
        assertThat(lottery.allowed(5_000, 20, 0)).isEqualTo(20);
        assertThat(lottery.allowed(95, 20, 100)).isEqualTo(5);
    }
}
