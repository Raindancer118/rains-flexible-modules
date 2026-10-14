package de.raindancer.modules.twitch;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.store.Secrets;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.testkit.TestDatabases;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.twitch.command.TwitchCommand;
import de.raindancer.modules.twitch.service.TwitchLoginStore;
import de.raindancer.modules.twitch.service.WatchRole;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("database")
class TwitchLoginCommandTest {

    private static final String SECRET = "very-secret-value";

    @TempDir
    Path folder;

    private Database database;
    private Secrets secrets;
    private Messages messages;
    private TwitchCommand command;
    private TwitchLoginStore logins;
    private final AtomicInteger reloads = new AtomicInteger();

    @BeforeEach
    void setUp() {
        database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE);
        secrets = new Secrets(database, System::currentTimeMillis);
        messages = mock(Messages.class);
        logins = new TwitchLoginStore(secrets, YamlStore.network(folder.resolve("credentials.yml")), Map.<String, String>of()::get,
                Log.of("test"));
        WatchRole role = mock(WatchRole.class);
        when(role.holder()).thenReturn(java.util.Optional.empty());
        TwitchServices services = new TwitchServices(null, null, Log.of("test"), messages, null,
                () -> TwitchSettings.DEFAULTS, null, () -> null, role, reloads::incrementAndGet, logins);
        command = new TwitchCommand(() -> services);
    }

    @AfterEach
    void close() {
        database.close();
    }

    private void run(CommandSender sender, String... args) {
        when(sender.hasPermission(anyString())).thenReturn(true);
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);
        command.execute(source, args);
    }

    private void assertNoSecretSaid() {
        assertThat(org.mockito.Mockito.mockingDetails(messages).getInvocations())
                .noneMatch(call -> java.util.Arrays.deepToString(call.getArguments()).contains(SECRET));
    }

    @Test
    @DisplayName("a player is refused, nothing is stored, and the secret is not echoed")
    void playerRefused() {
        Player player = mock(Player.class);

        run(player, "login", "client-id", SECRET);

        verify(messages).send(player, "twitch.login.console-only");
        assertThat(secrets.text(TwitchLoginStore.SECRET)).isEmpty();
        assertThat(reloads).hasValue(0);
    }

    @Test
    @DisplayName("a player cannot clear the login either")
    void playerCannotLogout() {
        logins.set("id", SECRET);
        Player player = mock(Player.class);

        run(player, "logout");

        verify(messages).send(player, "twitch.login.console-only");
        assertThat(secrets.text(TwitchLoginStore.SECRET)).isPresent();
    }

    @Test
    @DisplayName("the console stores the login, answers without it, and logout removes it")
    void consoleStoresAndClears() {
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);

        run(console, "login", "client-id", SECRET);

        assertThat(secrets.text(TwitchLoginStore.SECRET)).contains("client-id\n" + SECRET);
        assertThat(reloads).hasValue(1);
        verify(messages).send(console, "twitch.login.stored");
        assertNoSecretSaid();

        run(console, "logout");

        assertThat(secrets.text(TwitchLoginStore.SECRET)).isEmpty();
        verify(messages).send(console, "twitch.login.cleared");
    }

    @Test
    @DisplayName("a login with the wrong number of arguments stores nothing and shows only the usage")
    void wrongArguments() {
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);

        run(console, "login", SECRET);

        verify(messages).send(console, "twitch.login.usage");
        assertThat(secrets.text(TwitchLoginStore.SECRET)).isEmpty();
        assertNoSecretSaid();
    }

    @Test
    @DisplayName("status names where the login comes from, never its values")
    void statusNamesTheSource() {
        logins.set("client-id", SECRET);
        logins.read();
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);

        run(console, "status");

        verify(messages).send(console, "twitch.status.login.database");
        assertNoSecretSaid();
    }
}
