package de.raindancer.modules.economy.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hi-lo pays from the odds of what is left in the shoe, so the odds and the next card must come from the
 * same shoe — also on the card where a nearly empty shoe is swapped for a fresh one.
 */
@DisplayName("the odds shown and the card dealt come from the same shoe")
class ShoesTest {

    private final UUID player = UUID.randomUUID();

    @Test
    @DisplayName("on every card through two whole shoes, the card dealt is one the odds counted")
    void oddsMatchTheCard() {
        Shoes shoes = new Shoes(() -> 1, new Random(7));
        for (int i = 0; i < 120; i++) {
            int[] before = shoes.ranksLeft(player);
            Card dealt = shoes.draw(player);
            int[] after = shoes.ranksLeft(player);
            assertThat(before[dealt.rank()]).as("card %d (%s) was in the shoe the odds came from", i, dealt)
                    .isPositive();
            if (total(after) == 52) {
                // The shoe ran low with this card and the next odds come from a fresh one.
                continue;
            }
            for (int rank = 1; rank <= 13; rank++) {
                assertThat(after[rank]).as("card %d, rank %d", i, rank)
                        .isEqualTo(before[rank] - (rank == dealt.rank() ? 1 : 0));
            }
        }
    }

    @Test
    @DisplayName("a nearly empty shoe is swapped before anybody is shown odds from it")
    void swappedBeforeTheOdds() {
        Shoes shoes = new Shoes(() -> 1, new Random(3));
        for (int i = 0; i < 200; i++) {
            shoes.draw(player);
            assertThat(total(shoes.ranksLeft(player))).as("after card %d", i).isGreaterThanOrEqualTo(13);
        }
    }

    @Test
    @DisplayName("every player has their own shoe, and a forgotten player starts a fresh one")
    void perPlayer() {
        Shoes shoes = new Shoes(() -> 2, new Random(1));
        UUID other = UUID.randomUUID();
        shoes.draw(player);
        assertThat(total(shoes.ranksLeft(player))).isEqualTo(103);
        assertThat(total(shoes.ranksLeft(other))).isEqualTo(104);
        shoes.forget(player);
        assertThat(total(shoes.ranksLeft(player))).isEqualTo(104);
    }

    private static int total(int[] ranks) {
        int sum = 0;
        for (int count : ranks) {
            sum += count;
        }
        return sum;
    }
}
