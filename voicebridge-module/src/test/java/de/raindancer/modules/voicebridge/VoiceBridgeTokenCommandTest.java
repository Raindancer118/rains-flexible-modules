package de.raindancer.modules.voicebridge;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.store.Secrets;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.testkit.TestDatabases;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.voicebridge.command.VoiceBridgeCommand;
import de.raindancer.modules.voicebridge.store.BotTokens;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("database")
class VoiceBridgeTokenCommandTest {

    private static final String TOKEN = "MTIzNDU2.secret.token";
    private static final String NAME = "voicebridge-tokens-smp";

    @TempDir
    Path folder;

    private Database database;
    private Secrets secrets;
    private Messages messages;
    private BotTokens tokens;
    private VoiceBridgeCommand command;

    @BeforeEach
    void setUp() {
        database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE);
        secrets = new Secrets(database, System::currentTimeMillis);
        messages = mock(Messages.class);
        tokens = new BotTokens(secrets, "smp", new YamlStore(folder.resolve("discord-tokens.yml")),
                folder.resolve("discord-token.txt"), Map.<String, String>of()::get, Log.of("test"));
        VoiceBridgeServices services = new VoiceBridgeServices(null, null, Log.of("test"), messages, null, null,
                null, tokens, null, null, null, null, null, null);
        command = new VoiceBridgeCommand(() -> services);
    }

    @AfterEach
    void close() {
        database.close();
    }

    private void run(CommandSender sender, String... args) {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);
        command.execute(source, args);
    }

    private void assertNoTokenSaid() {
        assertThat(mockingDetails(messages).getInvocations())
                .noneMatch(call -> java.util.Arrays.deepToString(call.getArguments()).contains(TOKEN));
    }

    @Test
    @DisplayName("a player is refused, nothing is stored, and the token is not echoed")
    void playerRefused() {
        Player player = mock(Player.class);

        run(player, "token", "add", TOKEN);
        run(player, "token", "clear");

        verify(messages, org.mockito.Mockito.times(2)).send(player, "voicebridge.token.console-only");
        assertThat(secrets.text(NAME)).isEmpty();
        assertNoTokenSaid();
    }

    @Test
    @DisplayName("a player cannot clear stored tokens")
    void playerCannotClear() {
        tokens.add(TOKEN);

        run(mock(Player.class), "token", "clear");

        assertThat(secrets.text(NAME)).contains(TOKEN);
    }

    @Test
    @DisplayName("the console adds tokens in order, answers without them, and clear removes them")
    void consoleAddsAndClears() {
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);

        run(console, "token", "add", TOKEN);
        run(console, "token", "add", "second.token");

        assertThat(secrets.text(NAME)).contains(TOKEN + "\nsecond.token");
        verify(messages, org.mockito.Mockito.times(2)).send(console, "voicebridge.token.added");
        assertNoTokenSaid();

        run(console, "token", "clear");

        assertThat(secrets.text(NAME)).isEmpty();
        verify(messages).send(console, "voicebridge.token.cleared");
    }

    @Test
    @DisplayName("a malformed token command stores nothing and shows only the usage")
    void usage() {
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);

        run(console, "token", "add");
        run(console, "token", "add", TOKEN, "extra");

        verify(messages, org.mockito.Mockito.times(2)).send(console, "voicebridge.token.usage");
        assertThat(secrets.text(NAME)).isEmpty();
        assertNoTokenSaid();
    }
}
