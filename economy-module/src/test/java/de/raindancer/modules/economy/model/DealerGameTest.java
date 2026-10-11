package de.raindancer.modules.economy.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DealerGameTest {

    @Test
    @DisplayName("every game of chance has a dealer of its own, and each dealer deals the game it says")
    void everyGameHasADealer() {
        for (Game game : Game.values()) {
            assertThat(DealerGame.of(game).game()).as(game.title()).contains(game);
        }
        assertThat(DealerGame.CASINO.game()).isEmpty();
    }

    @Test
    @DisplayName("staff type a dealer's game as they like")
    void read() {
        assertThat(DealerGame.read("coinflip")).contains(DealerGame.COINFLIP);
        assertThat(DealerGame.read(" Scratch ")).contains(DealerGame.SCRATCH);
        assertThat(DealerGame.read("poker")).isEmpty();
        assertThat(DealerGame.COINFLIP.dealer()).isEqualTo("Coin flip dealer");
    }
}
