package de.raindancer.modules.economy;

import de.raindancer.modules.api.ModuleCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What the module declares at bootstrap, and how it behaves before it is running. */
class EconomyCommandsTest {

    @Test
    @DisplayName("every command can be declared without the module running, each with a handler")
    void declaring() {
        List<ModuleCommand> declared = EconomyCommands.declared();
        assertThat(declared).extracting(ModuleCommand::name).containsExactly("balance", "pay", "bill", "hire", "bank",
                "withdraw", "deposit", "shop", "sell", "baltop", "daily", "casino", "slots", "coinflip", "dice",
                "roulette", "lottery", "blackjack", "baccarat", "hilo", "mines", "crash", "race", "scratch", "auction", "raffle", "eco");
        assertThat(declared).allMatch(command -> command.handler() != null);
        assertThat(declared.getFirst().names()).contains("bal", "money");
    }

    @Test
    @DisplayName("running one while the module is stopped says so rather than throwing a null")
    void stopped() {
        EconomyCommands.stopped();
        assertThat(EconomyCommands.isRunning()).isFalse();
        assertThatThrownBy(() -> EconomyCommands.declared().getFirst().handler().execute(null, new String[0]))
                .hasMessageContaining("not running");
    }
}
