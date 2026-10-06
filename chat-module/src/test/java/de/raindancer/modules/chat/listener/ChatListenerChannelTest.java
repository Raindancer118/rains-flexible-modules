package de.raindancer.modules.chat.listener;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.ui.chat.Audiences;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.identity.Identities;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.chat.ChatServices;
import de.raindancer.modules.chat.ChatSettings;
import de.raindancer.modules.chat.service.ChatHistoryService;
import de.raindancer.modules.chat.service.ChatQualityService;
import de.raindancer.modules.chat.service.FormatService;
import de.raindancer.modules.chat.service.FreezeService;
import de.raindancer.modules.chat.service.MentionService;
import de.raindancer.modules.chat.service.PrivateChatService;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import de.raindancer.core.ui.chat.ChatChannel;
import de.raindancer.core.ui.chat.ChatChannels;
import org.junit.jupiter.api.AfterEach;

import java.util.Optional;
import java.util.Set;

/** A team line: cancelled, tagged, delivered to the team alone, and kept out of the public history. */
class ChatListenerChannelTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final Server server = mock(Server.class);
    private final Messages messages = mock(Messages.class);
    private final Chat chat = new Chat(new Brand("Rain"), mock(Audiences.class));
    private final FormatService format = new FormatService(chat, new Identities(mock(Database.class)),
            null, ChatSettings.DEFAULTS);
    private final ChatHistoryService history = mock(ChatHistoryService.class);
    private final ChatQualityService quality = mock(ChatQualityService.class);
    private final MentionService mentions = mock(MentionService.class);
    private final ChatListener listener = new ChatListener(new ChatServices(null, server, null, null,
            messages, chat, chat.brand(), () -> ChatSettings.DEFAULTS, format, mentions, quality,
            new FreezeService(), history, null, new PrivateChatService(), null));

    private final Player alice = player("Alice");
    private final Player bob = player("Bob");
    private final Player eve = player("Eve");

    private final ChatChannel team = new ChatChannel() {
        @Override
        public String id() {
            return "team";
        }

        @Override
        public String label() {
            return "Team";
        }

        @Override
        public Optional<Set<UUID>> audienceFor(UUID speaker) {
            return Optional.of(Set.of(alice.getUniqueId(), bob.getUniqueId()));
        }

        @Override
        public String tagFor(UUID speaker) {
            return "[Runners]";
        }
    };

    ChatListenerChannelTest() {
        when(messages.raw("chat.channel.line")).thenReturn("<aqua><tag></aqua> <line>");
        when(quality.check(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(de.raindancer.core.platform.rule.Verdict.allowed());
    }

    @AfterEach
    void clean() {
        ChatChannels.unregister(team);
        ChatChannels.forget(alice.getUniqueId());
    }

    private Player player(String name) {
        Player who = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(who.getUniqueId()).thenReturn(id);
        when(who.getName()).thenReturn(name);
        when(server.getPlayer(id)).thenReturn(who);
        return who;
    }

    private AsyncChatEvent saying(Player who, String text) {
        AsyncChatEvent event = mock(AsyncChatEvent.class);
        when(event.getPlayer()).thenReturn(who);
        when(event.message()).thenReturn(Component.text(text));
        return event;
    }

    @Test
    @DisplayName("a team line reaches the team, tagged, and nobody else")
    void teamOnly() {
        ChatChannels.register(team);
        ChatChannels.select(alice.getUniqueId(), "team");
        AsyncChatEvent event = saying(alice, "hunter at the village");

        listener.onChat(event);

        verify(event).setCancelled(true);
        ArgumentCaptor<Component> toBob = ArgumentCaptor.forClass(Component.class);
        verify(bob).sendMessage(toBob.capture());
        assertThat(PLAIN.serialize(toBob.getValue())).isEqualTo("[Runners] Alice: hunter at the village");
        verify(alice).sendMessage(any(Component.class));
        verify(eve, never()).sendMessage(any(Component.class));
        verifyNoInteractions(history);
    }

    @Test
    @DisplayName("somebody talking to everybody is untouched")
    void publicUntouched() {
        ChatChannels.register(team);
        AsyncChatEvent event = saying(alice, "gg");

        listener.onChat(event);

        verify(event, never()).setCancelled(true);
        verify(event).renderer(any());
    }

    @Test
    @DisplayName("a channel that says its own lines (staff chat) gets the line, and chat says nothing")
    void channelDeliversItself() {
        java.util.List<String> delivered = new java.util.ArrayList<>();
        ChatChannel staff = new ChatChannel() {
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
                return java.util.Optional.of(java.util.Set.of(alice.getUniqueId(), bob.getUniqueId()));
            }

            @Override
            public boolean deliver(Player speaker, String text) {
                delivered.add(speaker.getName() + ": " + text);
                return true;
            }
        };
        ChatChannels.register(staff);
        try {
            ChatChannels.select(alice.getUniqueId(), "staff");
            AsyncChatEvent event = saying(alice, "ban him");

            listener.onChat(event);

            verify(event).setCancelled(true);
            assertThat(delivered).containsExactly("Alice: ban him");
            verify(bob, never()).sendMessage(any(Component.class));
            verifyNoInteractions(history);
        } finally {
            ChatChannels.unregister(staff);
        }
    }
}
