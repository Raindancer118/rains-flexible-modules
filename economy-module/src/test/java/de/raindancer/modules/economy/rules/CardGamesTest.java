package de.raindancer.modules.economy.rules;

import de.raindancer.modules.economy.model.Card;
import de.raindancer.modules.economy.model.Shoe;
import de.raindancer.modules.economy.model.Suit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CardGamesTest {

    private static Card c(int rank) {
        return new Card(rank, Suit.SPADES);
    }

    @Test
    @DisplayName("a shoe of six decks holds 312 cards, every card six times")
    void shoe() {
        Shoe shoe = new Shoe(6, new Random(1));
        assertThat(shoe.left()).isEqualTo(312);
        int[] ranks = shoe.ranksLeft();
        for (int rank = 1; rank <= 13; rank++) {
            assertThat(ranks[rank]).isEqualTo(24);
        }
        HashSet<Card> seen = new HashSet<>();
        for (int i = 0; i < 52; i++) {
            seen.add(shoe.draw());
        }
        assertThat(shoe.left()).isEqualTo(260);
        assertThat(seen).hasSizeGreaterThan(30);
        assertThat(c(1).label()).isEqualTo("A♠");
        assertThat(new Card(12, Suit.HEARTS).fullName()).isEqualTo("Queen of Hearts");
    }

    @Nested
    @DisplayName("blackjack")
    class Blackjack {
        private final BlackjackRule rule = new BlackjackRule();

        @Test
        @DisplayName("aces count eleven when they can, one when they must")
        void totals() {
            assertThat(rule.total(List.of(c(1), c(13)))).isEqualTo(21);
            assertThat(rule.natural(List.of(c(1), c(13)))).isTrue();
            assertThat(rule.total(List.of(c(1), c(1), c(9)))).isEqualTo(21);
            assertThat(rule.total(List.of(c(1), c(6), c(9)))).isEqualTo(16);
            assertThat(rule.soft(List.of(c(1), c(6)))).isTrue();
            assertThat(rule.bust(List.of(c(10), c(5), c(9)))).isTrue();
        }

        @Test
        @DisplayName("the dealer draws to sixteen and stands on every seventeen, soft too")
        void dealer() {
            assertThat(rule.dealerDraws(List.of(c(10), c(6)))).isTrue();
            assertThat(rule.dealerDraws(List.of(c(1), c(6)))).isFalse();
            assertThat(rule.dealerDraws(List.of(c(10), c(7)))).isFalse();
        }

        @Test
        @DisplayName("a natural pays three to two, a push returns the stake, a bust loses whatever the dealer has")
        void returns() {
            List<Card> natural = List.of(c(1), c(12));
            assertThat(rule.returns(natural, List.of(c(10), c(9)), false)).isEqualTo(2.5);
            assertThat(rule.returns(natural, List.of(c(1), c(10)), false)).isEqualTo(1);
            assertThat(rule.returns(natural, List.of(c(10), c(9)), true)).as("21 after a split").isEqualTo(2);
            assertThat(rule.returns(List.of(c(10), c(8)), List.of(c(10), c(8)), false)).isEqualTo(1);
            assertThat(rule.returns(List.of(c(10), c(5), c(9)), List.of(c(10), c(6), c(9)), false)).isZero();
            assertThat(rule.returns(List.of(c(10), c(8)), List.of(c(10), c(6), c(9)), false)).isEqualTo(2);
            assertThat(rule.returns(List.of(c(10), c(8)), List.of(c(1), c(10)), false)).isZero();
        }

        @Test
        @DisplayName("pairs of the same value split, and only two-card hands double")
        void options() {
            assertThat(rule.canSplit(List.of(c(8), new Card(8, Suit.HEARTS)))).isTrue();
            assertThat(rule.canSplit(List.of(c(10), c(13)))).as("ten and king are both ten").isTrue();
            assertThat(rule.canSplit(List.of(c(8), c(9)))).isFalse();
            assertThat(rule.canDouble(List.of(c(5), c(6), c(2)))).isFalse();
        }
    }

    @Nested
    @DisplayName("baccarat")
    class Baccarat {
        private final BaccaratRule rule = new BaccaratRule();

        @Test
        @DisplayName("tens and faces count nothing and a hand is its last digit")
        void points() {
            assertThat(rule.points(List.of(c(13), c(9)))).isEqualTo(9);
            assertThat(rule.points(List.of(c(7), c(8)))).isEqualTo(5);
            assertThat(rule.natural(List.of(c(4), c(4)))).isTrue();
        }

        @Test
        @DisplayName("the banker follows the real third-card table")
        void banker() {
            assertThat(rule.bankerDraws(List.of(c(1), c(2)), c(8))).as("3 against an 8").isFalse();
            assertThat(rule.bankerDraws(List.of(c(1), c(2)), c(9))).isTrue();
            assertThat(rule.bankerDraws(List.of(c(2), c(2)), c(1))).as("4 against an ace").isFalse();
            assertThat(rule.bankerDraws(List.of(c(3), c(3)), c(6))).isTrue();
            assertThat(rule.bankerDraws(List.of(c(3), c(3)), null)).as("6 and the player stood").isFalse();
            assertThat(rule.bankerDraws(List.of(c(2), c(3)), null)).isTrue();
        }

        @Test
        @DisplayName("player even money, banker less five percent, tie eight to one, ties return player and banker")
        void returns() {
            assertThat(rule.returns(BaccaratRule.Side.PLAYER, BaccaratRule.Side.PLAYER)).isEqualTo(2);
            assertThat(rule.returns(BaccaratRule.Side.BANKER, BaccaratRule.Side.BANKER)).isEqualTo(1.95);
            assertThat(rule.returns(BaccaratRule.Side.TIE, BaccaratRule.Side.TIE)).isEqualTo(9);
            assertThat(rule.returns(BaccaratRule.Side.PLAYER, BaccaratRule.Side.TIE)).isEqualTo(1);
            assertThat(rule.returns(BaccaratRule.Side.PLAYER, BaccaratRule.Side.BANKER)).isZero();
        }
    }

    @Test
    @DisplayName("hi-lo pays exactly one minus the edge for every guess, from the cards really left")
    void hiLo() {
        HiLoRule rule = new HiLoRule();
        int[] fresh = new Shoe(1, new Random(2)).ranksLeft();
        for (int rank = 1; rank <= 13; rank++) {
            for (boolean higher : new boolean[]{true, false}) {
                double chance = rule.chance(rank, higher, fresh);
                if (chance > 0) {
                    assertThat(chance * rule.step(chance, 0.03)).isCloseTo(0.97, within(1e-9));
                }
            }
        }
        assertThat(rule.chance(13, true, fresh)).as("nothing beats a king").isZero();
        assertThat(rule.chance(7, true, fresh)).isEqualTo(24 / 52.0);
        assertThat(rule.wins(7, 7, true)).as("the same rank loses").isFalse();
    }

    @Test
    @DisplayName("crash reaches any multiplier with exactly the chance that makes it fair less the edge")
    void crash() {
        CrashRule rule = new CrashRule();
        int samples = 200_000;
        int reached = 0;
        Random random = new Random(3);
        for (int i = 0; i < samples; i++) {
            if (rule.crashPoint(1 - random.nextDouble(), 0.03) >= 2.0) {
                reached++;
            }
        }
        assertThat(reached / (double) samples).isCloseTo(0.485, within(0.005));
        assertThat(rule.crashPoint(1.0, 0.03)).isEqualTo(1.0);
        assertThat(rule.crashPoint(0.5, 0)).isEqualTo(2.0);
        assertThat(rule.multiplierAt(0)).isEqualTo(1.0);
        assertThat(rule.multiplierAt(11_500)).isEqualTo(2.0);
    }

    @Test
    @DisplayName("a crash round ends by 1,000× at the latest, so no round can hold the game up for long")
    void crashCapped() {
        CrashRule rule = new CrashRule();
        assertThat(rule.crashPoint(1e-12, 0.03)).isEqualTo(CrashRule.MOST);
        assertThat(rule.crashPoint(0.5, 0.0)).isEqualTo(2.0);
    }

    @Test
    @DisplayName("an auto cash-out is paid whenever the round reached it — also when the same step crashed")
    void crashAutoCashOut() {
        CrashRule rule = new CrashRule();
        assertThat(rule.autoCashesOut(1.5, 1.53, 1.52)).as("passed on the way to the crash").isTrue();
        assertThat(rule.autoCashesOut(1.5, 1.53, 1.50)).as("reaching it is enough").isTrue();
        assertThat(rule.autoCashesOut(1.5, 1.53, 1.49)).as("crashed before it").isFalse();
        assertThat(rule.autoCashesOut(1.5, 1.49, 3.0)).as("not there yet").isFalse();
        assertThat(rule.autoCashesOut(1.0, 2.0, 3.0)).as("1× is no target").isFalse();
    }

    @Test
    @DisplayName("mines pays exactly one minus the edge after any number of safe tiles")
    void mines() {
        MinesRule rule = new MinesRule();
        for (int mines = 1; mines <= 24; mines++) {
            for (int picks = 1; picks <= 25 - mines; picks++) {
                assertThat(rule.survival(mines, picks) * rule.multiplier(mines, picks, 0.03))
                        .isCloseTo(0.97, within(1e-9));
            }
        }
        assertThat(rule.survival(3, 23)).isZero();
        assertThat(rule.validMines(0)).isFalse();
        assertThat(rule.validMines(25)).isFalse();
    }

    @Test
    @DisplayName("a scratch card returns exactly one minus the edge, and shows exactly what it won")
    void scratch() {
        ScratchRule rule = new ScratchRule();
        double expected = 0;
        for (ScratchRule.Prize prize : ScratchRule.Prize.values()) {
            expected += prize.chance() * rule.multiplier(prize, 0.03);
        }
        assertThat(expected).isCloseTo(0.97, within(1e-9));
        assertThat(rule.draw(0.0)).isEqualTo(ScratchRule.Prize.CLOVER);
        assertThat(rule.draw(0.9999)).isNull();
        Random random = new Random(4);
        for (ScratchRule.Prize won : new ScratchRule.Prize[]{null, ScratchRule.Prize.STAR}) {
            for (int i = 0; i < 200; i++) {
                List<ScratchRule.Prize> fields = rule.fields(won, random);
                assertThat(fields).hasSize(9);
                for (ScratchRule.Prize prize : ScratchRule.Prize.values()) {
                    long count = fields.stream().filter(field -> field == prize).count();
                    assertThat(count >= 3).isEqualTo(prize == won);
                }
            }
        }
    }

    @Test
    @DisplayName("a horse race pays fair odds less the edge, and the drawn horse always wins alone")
    void race() {
        HorseRaceRule rule = new HorseRaceRule();
        double totalChance = 0;
        for (int horse = 0; horse < rule.horses(); horse++) {
            totalChance += rule.chance(horse);
            assertThat(rule.chance(horse) * rule.pays(horse, 0.03)).isCloseTo(0.97, within(1e-9));
        }
        assertThat(totalChance).isCloseTo(1.0, within(1e-9));
        Random random = new Random(5);
        for (int i = 0; i < 300; i++) {
            int winner = rule.winner(random.nextDouble());
            List<List<Integer>> race = rule.race(winner, random);
            int winnerFrame = race.get(winner).indexOf(HorseRaceRule.TRACK);
            for (int horse = 0; horse < race.size(); horse++) {
                if (horse != winner) {
                    assertThat(race.get(horse).indexOf(HorseRaceRule.TRACK)).isGreaterThan(winnerFrame);
                }
                assertThat(race.get(horse)).hasSize(race.get(winner).size());
            }
        }
    }
}
