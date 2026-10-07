package de.raindancer.modules.essentials.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Player#chat runs anything starting with a slash as a command, as the player. A roast that opens with
 * its target's name, and a target nicknamed "/op Bo", would otherwise make the roaster op somebody.
 */
class FunServiceTest {

    @Test
    @DisplayName("a line can never reach chat as a command")
    void neverACommand() {
        assertThat(FunService.chatSafe("/op Bo, you mine straight down.")).isEqualTo("op Bo, you mine straight down.");
        assertThat(FunService.chatSafe("  //  /stop")).isEqualTo("stop");
        assertThat(FunService.chatSafe("Bo, you mine straight down.")).isEqualTo("Bo, you mine straight down.");
        assertThat(FunService.chatSafe("Why? Because/so.")).isEqualTo("Why? Because/so.");
    }
}
