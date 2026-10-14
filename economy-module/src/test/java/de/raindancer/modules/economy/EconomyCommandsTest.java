package de.raindancer.modules.economy;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.economy.service.BankClient;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** What the module declares at bootstrap, and how it behaves before it is running. */
class EconomyCommandsTest {

    @Test
    @DisplayName("every command can be declared without the module running, each with a handler")
    void declaring() {
        List<ModuleCommand> declared = EconomyCommands.declared();
        assertThat(declared).extracting(ModuleCommand::name).containsExactly("balance", "pay", "bill", "hire", "contract", "loan", "bank",
                "withdraw", "deposit", "shop", "sell", "baltop", "daily", "claimadvancements", "casino", "slots", "coinflip", "dice",
                "roulette", "lottery", "blackjack", "baccarat", "hilo", "mines", "crash", "race", "scratch", "auction", "raffle", "giveaway", "treasury", "fund", "repair", "season", "eco");
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

    private final Messages messages = mock(Messages.class);
    private final BankClient bank = mock(BankClient.class);
    private final CommandSender sender = mock(CommandSender.class);
    private final CommandSourceStack source = mock(CommandSourceStack.class);

    @AfterEach
    void stop() {
        EconomyCommands.stopped();
    }

    private void client(boolean startedAsClient, boolean elsewhere) {
        when(bank.elsewhere()).thenReturn(elsewhere);
        when(bank.keepsIt()).thenReturn(!elsewhere);
        when(bank.bankName()).thenReturn("Survival");
        when(source.getSender()).thenReturn(sender);
        EconomyCommands.readyClient(new EconomyClient(null, null, messages, null, bank, startedAsClient));
    }

    private ModuleCommand declared(String name) {
        return EconomyCommands.declared().stream().filter(command -> command.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("on a server that is not the bank, a bank-only command says where the bank is instead of running")
    void clientRefusesBankCommands() throws Exception {
        client(true, true);

        for (String name : List.of("shop", "sell", "bill", "loan", "casino", "slots", "coinflip", "auction", "raffle",
                "lottery", "bank", "daily", "withdraw", "deposit", "hire", "contract", "treasury", "fund", "eco")) {
            declared(name).handler().execute(source, new String[0]);
            assertThat(declared(name).handler().suggest(source, new String[0])).isEmpty();
        }

        verify(messages, org.mockito.Mockito.times(19)).send(sender, "economy.client.on-the-bank", "server", "Survival");
    }

    @Test
    @DisplayName("a client whose server was made the bank says it needs a restart, rather than asking itself")
    void clientMovedToBankNeedsARestart() throws Exception {
        client(true, false);

        declared("shop").handler().execute(source, new String[0]);

        verify(messages).send(sender, "economy.client.restart");
    }

    @Test
    @DisplayName("a server running the bank itself never answers as a client")
    void bankDoesNotRouteAway() {
        client(false, false);

        assertThatThrownBy(() -> declared("shop").handler().execute(source, new String[0]))
                .hasMessageContaining("not running");
    }

    @Test
    @DisplayName("a bank that was moved away from a running server turns its bank-only commands into the refusal")
    void demotedBankRefuses() throws Exception {
        client(false, true);

        declared("shop").handler().execute(source, new String[0]);

        verify(messages).send(sender, "economy.client.on-the-bank", "server", "Survival");
    }
}
