package de.raindancer.modules.chat.command;

import de.raindancer.core.RainsCore;
import de.raindancer.core.moderation.vanish.Vanish;
import de.raindancer.core.moderation.vanish.VanishSink;
import de.raindancer.core.ui.chat.ChatButton;
import de.raindancer.core.ui.chat.ChatButtons;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.chat.ChatServices;
import de.raindancer.modules.chat.ChatSettings;
import de.raindancer.modules.chat.service.FreezeService;
import de.raindancer.modules.chat.service.MentionService;
import de.raindancer.modules.chat.service.PrivateChatService;
import de.raindancer.modules.chat.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

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

    private final ChatButtons buttons = mock(ChatButtons.class);
    private final ChatButton leave = mock(ChatButton.class);
    private final RainsCore core = mock(RainsCore.class);
    /** The [Accept] and [Deny] callbacks each invited player was handed, as though they could click. */
    private final Map<UUID, Consumer<UUID>> yes = new HashMap<>();
    private final Map<UUID, Consumer<UUID>> no = new HashMap<>();

    private final ChatServices services = new ChatServices(null, server, core, null, messages, null, null,
            () -> ChatSettings.DEFAULTS, null, mentions, null, freeze, null, null, privateChat, null);
    private final ChatCommand command = new ChatCommand(() -> services);

    private final Player alice = player("Alice");
    private final Player bob = player("Bob");
    private final Player carol = player("Carol");

    @BeforeEach
    @SuppressWarnings("unchecked")
    void buttons() {
        when(core.buttons()).thenReturn(buttons);
        when(messages.prefixed(any(String.class), any(Object[].class))).thenReturn(Component.text("words"));
        when(buttons.ask(any(UUID.class), any(Duration.class), any(Consumer.class), any(Consumer.class)))
                .thenAnswer(call -> {
                    yes.put(call.getArgument(0), call.getArgument(2));
                    no.put(call.getArgument(0), call.getArgument(3));
                    return Component.text("[Accept] [Deny]");
                });
        when(buttons.label(any(String.class))).thenReturn(leave);
        when(leave.tooltip(any(String.class))).thenReturn(leave);
        when(leave.runs(any(String.class))).thenReturn(leave);
        when(leave.render()).thenReturn(Component.text("[Leave]"));
    }

    private void click(Player who, boolean accept) {
        (accept ? yes : no).get(who.getUniqueId()).accept(who.getUniqueId());
    }

    /** {@code owner} invites each of {@code joiners}, and each clicks [Accept]. */
    private void inChat(Player owner, Player... joiners) {
        for (Player joiner : joiners) {
            run(owner, "private", joiner.getName());
            click(joiner, true);
        }
    }

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
    @DisplayName("/chat private <name> invites them — they are not in until they accept")
    void invitesByName() {
        run(alice, "private", "Bob");

        assertThat(privateChat.chatOf(bob.getUniqueId())).isEmpty();
        verify(messages).send(alice, "chat.private.started");
        verify(messages).send(alice, "chat.private.invited", "player", "Bob", "seconds", 60L);
        verify(buttons).ask(eq(bob.getUniqueId()), eq(PrivateChatService.INVITE_STANDS), any(), any());
        verify(bob).sendMessage(any(Component.class));
    }

    @Test
    @DisplayName("the invitation goes to the invited player alone")
    void invitationIsPrivate() {
        run(alice, "private", "Bob");

        verify(carol, never()).sendMessage(any(Component.class));
        verify(server, never()).broadcast(any(Component.class));
        verify(server, never()).getConsoleSender();
    }

    @Test
    @DisplayName("clicking [Accept] puts them in, switches them over, offers [Leave], and tells the rest")
    void acceptingJoins() {
        run(alice, "private", "Bob");

        click(bob, true);

        assertThat(privateChat.isTalkingPrivately(bob.getUniqueId())).isTrue();
        assertThat(privateChat.readersOf(alice.getUniqueId())).contains(bob.getUniqueId());
        verify(messages).prefixed("chat.private.you-joined", "owner", "Alice");
        verify(buttons).label("<red>[Leave]</red>");
        verify(leave).runs("/chat private leave");
        verify(messages).send(alice, "chat.private.added", "player", "Bob");
    }

    @Test
    @DisplayName("clicking [Deny] leaves them out and tells the inviter")
    void decliningStaysOut() {
        run(alice, "private", "Bob");

        click(bob, false);

        assertThat(privateChat.chatOf(bob.getUniqueId())).isEmpty();
        verify(messages).send(bob, "chat.private.you-declined", "owner", "Alice");
        verify(messages).send(alice, "chat.private.declined", "player", "Bob");
    }

    @Test
    @DisplayName("/chat private accept <name> works as well as the button, once")
    void typedAccept() {
        run(alice, "private", "add", "Bob");

        run(bob, "private", "accept", "Alice");
        run(bob, "private", "leave");
        run(bob, "private", "accept", "Alice");

        assertThat(privateChat.chatOf(bob.getUniqueId())).isEmpty();
        verify(messages).send(bob, "chat.private.invite-gone");
    }

    @Test
    @DisplayName("nobody can join a chat they were never invited to")
    void noInvitationNoEntry() {
        run(alice, "private");

        run(bob, "private", "accept", "Alice");

        assertThat(privateChat.chatOf(bob.getUniqueId())).isEmpty();
        verify(messages).send(bob, "chat.private.invite-gone");
    }

    @Test
    @DisplayName("inviting the same person again while the first invitation stands is refused")
    void noSpam() {
        run(alice, "private", "Bob");
        run(alice, "private", "Bob");

        verify(messages).send(alice, "chat.private.already-invited", "player", "Bob");
        verify(buttons).ask(eq(bob.getUniqueId()), any(), any(), any());
    }

    @Test
    @DisplayName("several names at once are each invited")
    void invitesSeveral() {
        inChat(alice, bob, carol);
        click(bob, true);
        click(carol, true);

        assertThat(privateChat.readersOf(alice.getUniqueId()))
                .containsExactlyInAnyOrder(alice.getUniqueId(), bob.getUniqueId(), carol.getUniqueId());
    }

    @Test
    @DisplayName("a player who is offline — or vanished from the one asking — is not found")
    void notOnline() {
        run(alice, "private", "Nobody");

        verify(messages).send(alice, "chat.not-online", "player", "Nobody");
        assertThat(privateChat.chatOf(alice.getUniqueId())).isEmpty();
    }

    @Test
    @DisplayName("/chat public switches back, and /chat private switches in again")
    void publicAndBack() {
        inChat(alice, bob);

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
        inChat(alice, bob, carol);

        run(alice, "private", "end");

        assertThat(privateChat.chatOf(bob.getUniqueId())).isEmpty();
        verify(messages).send(bob, "chat.private.ended", "player", "Alice");
        verify(messages).send(carol, "chat.private.ended", "player", "Alice");
    }

    @Test
    @DisplayName("a member cannot end the chat")
    void memberCannotEnd() {
        inChat(alice, bob);

        run(bob, "private", "end");

        assertThat(privateChat.chatOf(alice.getUniqueId())).isPresent();
        verify(messages).send(bob, "chat.private.not-the-owner", "owner", "Alice");
    }

    @Test
    @DisplayName("/chat private leave takes a member out and tells the rest")
    void leaves() {
        inChat(alice, bob, carol);

        run(bob, "private", "leave");

        assertThat(privateChat.chatOf(bob.getUniqueId())).isEmpty();
        verify(messages).send(bob, "chat.private.you-left");
        verify(messages).send(alice, "chat.private.left", "player", "Bob");
        verify(messages, never()).send(eq(bob), eq("chat.private.left"), any(Object[].class));
    }

    @Test
    @DisplayName("/chat private remove takes somebody out and tells them")
    void removes() {
        inChat(alice, bob);

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

        assertThat(command.suggest(source, new String[]{""})).containsExactly("all", "team", "private", "public");
        assertThat(command.suggest(source, new String[]{"private", ""}))
                .contains("add", "remove", "leave", "end", "list", "accept", "decline");
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
        inChat(alice, bob);

        run(bob, "private", "list");

        verify(messages).send(eq(bob), eq("chat.private.members"), eq("owner"), eq("Alice"),
                eq("count"), eq("2"), eq("members"), any(String.class));
    }

    @Test
    @DisplayName("/chat all leaves the private chat's talking too — everybody means everybody")
    void allMeansEverybody() {
        run(alice, "private");

        run(alice, "all");

        assertThat(privateChat.isTalkingPrivately(alice.getUniqueId())).isFalse();
        assertThat(privateChat.chatOf(alice.getUniqueId())).isPresent();
    }

    @Test
    @DisplayName("/chat public leaves a chosen channel too, not only a private chat")
    void publicLeavesAChannel() {
        de.raindancer.core.ui.chat.ChatChannel staff = new de.raindancer.core.ui.chat.ChatChannel() {
            @Override
            public String id() {
                return "staff";
            }

            @Override
            public String label() {
                return "Staff";
            }

            @Override
            public java.util.Optional<java.util.Set<UUID>> audienceFor(UUID speaker) {
                return java.util.Optional.of(java.util.Set.of(speaker));
            }
        };
        de.raindancer.core.ui.chat.ChatChannels.register(staff);
        try {
            de.raindancer.core.ui.chat.ChatChannels.select(alice.getUniqueId(), "staff");

            run(alice, "public");

            assertThat(de.raindancer.core.ui.chat.ChatChannels.selected(alice.getUniqueId()))
                    .isEqualTo(de.raindancer.core.ui.chat.ChatChannels.ALL);
            verify(messages).send(alice, "chat.channel.now", "channel", "All");
        } finally {
            de.raindancer.core.ui.chat.ChatChannels.unregister(staff);
            de.raindancer.core.ui.chat.ChatChannels.forget(alice.getUniqueId());
        }
    }
}
