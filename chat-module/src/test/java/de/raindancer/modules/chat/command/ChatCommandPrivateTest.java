package de.raindancer.modules.chat.command;

import de.raindancer.core.moderation.vanish.Vanish;
import de.raindancer.core.moderation.vanish.VanishSink;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.chat.ChatServices;
import de.raindancer.modules.chat.ChatSettings;
import de.raindancer.modules.chat.service.FreezeService;
import de.raindancer.modules.chat.service.MentionService;
import de.raindancer.modules.chat.service.PrivateChatService;
import de.raindancer.modules.chat.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatCommandPrivateTest {

    private final Server server = mock(Server.class);
    private final Messages messages = mock(Messages.class);
    private final FreezeService freeze = new FreezeService();
    private final PrivateChatService privateChat = new PrivateChatService();
    private final Vanish vanish = new Vanish(mock(VanishSink.class));
    private final MentionService mentions = new MentionService(server, vanish, messages, ChatSettings.DEFAULTS);

    private final ChatServices services = new ChatServices(null, server, null, null, messages, null, null,
            () -> ChatSettings.DEFAULTS, null, mentions, null, freeze, null, null, privateChat);
    private final ChatCommand command = new ChatCommand(() -> services);

    private final Player alice = player("Alice");
    private final Player bob = player("Bob");
    private final Player carol = player("Carol");

    private Player player(String name) {
        Player who = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(who.getUniqueId()).thenReturn(id);
        when(who.getName()).thenReturn(name);
        when(who.hasPermission(PermissionNodes.PRIVATE)).thenReturn(true);
        when(server.getPlayer(id)).thenReturn(who);
        when(server.getPlayerExact(name)).thenReturn(who);
        return who;
    }

    private void run(Player who, String... args) {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(who);
        command.execute(source, args);
    }

    @Test
    @DisplayName("anybody may run /chat — the staff subcommands check for themselves")
    void openToEverybody() {
        assertThat(command.permission()).isNull();
    }

    @Test
    @DisplayName("somebody without chat.admin cannot freeze chat")
    void staffToolsStillGuarded() {
        run(alice, "freeze");

        assertThat(freeze.isFrozen()).isFalse();
        verify(messages).send(alice, "chat.no-permission");
    }

    @Test
    @DisplayName("/chat private starts a chat and switches the sender into it")
    void starts() {
        run(alice, "private");

        assertThat(privateChat.isTalkingPrivately(alice.getUniqueId())).isTrue();
        verify(messages).send(alice, "chat.private.started");
    }

    @Test
    @DisplayName("/chat private add puts the player in, switches them over, and tells everybody")
    void adds() {
        run(alice, "private");
        run(alice, "private", "add", "Bob");

        assertThat(privateChat.isTalkingPrivately(bob.getUniqueId())).isTrue();
        verify(messages).send(bob, "chat.private.you-were-added", "owner", "Alice");
        verify(messages).send(alice, "chat.private.added", "player", "Bob");
    }

    @Test
    @DisplayName("several names at once are each added")
    void addsSeveral() {
        run(alice, "private", "add", "Bob", "Carol");

        assertThat(privateChat.readersOf(alice.getUniqueId()))
                .containsExactlyInAnyOrder(alice.getUniqueId(), bob.getUniqueId(), carol.getUniqueId());
    }

    @Test
    @DisplayName("a player who is offline — or vanished from the one asking — is not found")
    void notOnline() {
        run(alice, "private", "add", "Nobody");

        verify(messages).send(alice, "chat.not-online", "player", "Nobody");
        assertThat(privateChat.chatOf(alice.getUniqueId())).isEmpty();
    }

    @Test
    @DisplayName("/chat public switches back, and /chat private switches in again")
    void publicAndBack() {
        run(alice, "private", "add", "Bob");

        run(bob, "public");
        assertThat(privateChat.isTalkingPrivately(bob.getUniqueId())).isFalse();
        verify(messages).send(bob, "chat.private.public");

        run(bob, "private");
        assertThat(privateChat.isTalkingPrivately(bob.getUniqueId())).isTrue();
        verify(messages).send(bob, "chat.private.switched");
    }

    @Test
    @DisplayName("/chat private end closes it for everybody and tells every other member")
    void ends() {
        run(alice, "private", "add", "Bob", "Carol");

        run(alice, "private", "end");

        assertThat(privateChat.chatOf(bob.getUniqueId())).isEmpty();
        verify(messages).send(bob, "chat.private.ended", "player", "Alice");
        verify(messages).send(carol, "chat.private.ended", "player", "Alice");
    }

    @Test
    @DisplayName("a member cannot end the chat")
    void memberCannotEnd() {
        run(alice, "private", "add", "Bob");

        run(bob, "private", "end");

        assertThat(privateChat.chatOf(alice.getUniqueId())).isPresent();
        verify(messages).send(bob, "chat.private.not-the-owner", "owner", "Alice");
    }

    @Test
    @DisplayName("/chat private leave takes a member out and tells the rest")
    void leaves() {
        run(alice, "private", "add", "Bob", "Carol");

        run(bob, "private", "leave");

        assertThat(privateChat.chatOf(bob.getUniqueId())).isEmpty();
        verify(messages).send(bob, "chat.private.you-left");
        verify(messages).send(alice, "chat.private.left", "player", "Bob");
        verify(messages, never()).send(eq(bob), eq("chat.private.left"), any(Object[].class));
    }

    @Test
    @DisplayName("/chat private remove takes somebody out and tells them")
    void removes() {
        run(alice, "private", "add", "Bob");

        run(alice, "private", "remove", "Bob");

        assertThat(privateChat.chatOf(bob.getUniqueId())).isEmpty();
        verify(messages).send(bob, "chat.private.you-were-removed", "owner", "Alice");
    }

    @Test
    @DisplayName("somebody without chat.private cannot start one")
    void privateNeedsItsNode() {
        when(alice.hasPermission(PermissionNodes.PRIVATE)).thenReturn(false);

        run(alice, "private");

        assertThat(privateChat.chatOf(alice.getUniqueId())).isEmpty();
        verify(messages).send(alice, "chat.no-permission");
    }

    @Test
    @DisplayName("the staff subcommands are only suggested to staff")
    void suggestions() {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(alice);

        assertThat(command.suggest(source, new String[]{""})).containsExactly("private", "public");
        assertThat(command.suggest(source, new String[]{"private", ""}))
                .contains("add", "remove", "leave", "end", "list");
    }

    @Test
    @DisplayName("the console gets told it cannot have a private chat rather than an exception")
    void consoleRefused() {
        CommandSourceStack source = mock(CommandSourceStack.class);
        org.bukkit.command.CommandSender console = mock(org.bukkit.command.CommandSender.class);
        when(source.getSender()).thenReturn(console);
        when(console.hasPermission(any(String.class))).thenReturn(true);

        command.execute(source, new String[]{"private"});

        verify(messages).send(console, "chat.only-a-player");
    }

    @Test
    @DisplayName("/chat private list names every member")
    void lists() {
        run(alice, "private", "add", "Bob");

        run(bob, "private", "list");

        verify(messages).send(eq(bob), eq("chat.private.members"), eq("owner"), eq("Alice"),
                eq("count"), eq("2"), eq("members"), any(String.class));
    }
}
