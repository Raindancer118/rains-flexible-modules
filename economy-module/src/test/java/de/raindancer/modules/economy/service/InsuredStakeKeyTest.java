package de.raindancer.modules.economy.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InsuredStakeKeyTest {

    private final UUID ada = UUID.randomUUID();

    @Test
    @DisplayName("an insured stake is remembered per player and game, so two games at once never mix")
    void perGame() {
        assertThat(GamblingService.insuredKey(ada, "Crash")).isNotEqualTo(GamblingService.insuredKey(ada, "Horse race"));
        assertThat(GamblingService.insuredKey(ada, "Crash"))
                .isNotEqualTo(GamblingService.insuredKey(UUID.randomUUID(), "Crash"));
    }

    @Test
    @DisplayName("a doubled or split blackjack hand and the payout of the hand are the same game")
    void sameGame() {
        assertThat(GamblingService.insuredKey(ada, "Blackjack, doubled"))
                .isEqualTo(GamblingService.insuredKey(ada, "Blackjack"));
        assertThat(GamblingService.insuredKey(ada, "Horse race: Thunder"))
                .isEqualTo(GamblingService.insuredKey(ada, "Horse race"));
    }
}
