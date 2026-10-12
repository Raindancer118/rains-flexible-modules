package de.raindancer.modules.chat.command;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.chat.ChatServices;
import de.raindancer.modules.chat.ChatSettings;
import de.raindancer.modules.chat.service.FreezeService;
import de.raindancer.modules.chat.service.PrivateChatService;
import de.raindancer.modules.chat.service.SayAsService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** /chat say <player> <message> — the console speaking in a player's name. */
class ChatCommandSayTest {

    private final Server server = mock(Server.class);
    private final Messages messages = mock(Messages.class);
    private final SayAsService sayAs = mock(SayAsService.class);
    private final ChatServices services = new ChatServices(null, server, null, null, messages, null, null,
            () -> ChatSettings.DEFAULTS, null, null, null, new FreezeService(), null, null,
            new PrivateChatService(), null, null, sayAs, null);
    private final ChatCommand command = new ChatCommand(() -> services);
    private final ConsoleCommandSender console = mock(ConsoleCommandSender.class);
    private final OfflinePlayer tom = mock(OfflinePlayer.class);

    ChatCommandSayTest() {
        when(tom.getUniqueId()).thenReturn(UUID.randomUUID());
        when(tom.getName()).thenReturn("Raindancer118");
        when(tom.hasPlayedBefore()).thenReturn(true);
        when(server.getOfflinePlayerIfCached("raindancer118")).thenReturn(tom);
        when(sayAs.say(any(), anyString())).thenReturn(SayAsService.Said.SAID);
    }

    private void run(CommandSender who, String... args) {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(who);
        command.execute(source, args);
    }

    @Test
    @DisplayName("the console says the rest of the line in the named player's name")
    void says() {
        run(console, "say", "raindancer118", "hello", "everybody");

        verify(sayAs).say(tom, "hello everybody");
    }

    @Test
    @DisplayName("RCON is the console too")
    void rcon() {
        run(mock(org.bukkit.command.RemoteConsoleCommandSender.class), "say", "raindancer118", "hi");

        verify(sayAs).say(tom, "hi");
    }

    @Test
    @DisplayName("a player cannot, not even staff: speaking for somebody else is the console's alone")
    void playersMayNot() {
        Player staff = mock(Player.class);
        when(staff.hasPermission(anyString())).thenReturn(true);

        run(staff, "say", "raindancer118", "hello");

        verify(sayAs, never()).say(any(), anyString());
        verify(messages).send(staff, "chat.say.console-only");
    }

    @Test
    @DisplayName("somebody who never played here is refused by name")
    void unknown() {
        run(console, "say", "Nobody", "hello");

        verify(sayAs, never()).say(any(), anyString());
        verify(messages).send(console, "chat.say.unknown", "player", "Nobody");
    }

    @Test
    @DisplayName("a mute is reported back")
    void muted() {
        when(sayAs.say(any(), anyString())).thenReturn(SayAsService.Said.MUTED);

        run(console, "say", "raindancer118", "hello");

        verify(messages).send(console, "chat.say.muted", "player", "Raindancer118");
    }

    @Test
    @DisplayName("without a message it shows how it is used")
    void usage() {
        run(console, "say", "raindancer118");

        verify(sayAs, never()).say(any(), anyString());
        verify(messages).send(eq(console), eq("chat.usage"), eq("usage"), anyString());
    }

    @Test
    @DisplayName("the console is offered say")
    void suggestsSay() {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(console);
        when(console.hasPermission(anyString())).thenReturn(true);

        assertThat(command.suggest(source, new String[]{"s"})).contains("say");
    }
}
