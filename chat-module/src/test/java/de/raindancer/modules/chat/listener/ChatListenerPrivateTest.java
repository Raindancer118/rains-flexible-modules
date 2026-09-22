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

/**
 * A line said in a private chat: cancelled — which is what keeps it out of the console, out of every
 * later listener that honours a cancel (the Discord bridge among them), and out of public chat — and
 * handed to the chat's members and nobody else.
 */
class ChatListenerPrivateTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final Server server = mock(Server.class);
    private final Messages messages = mock(Messages.class);
    private final Chat chat = new Chat(new Brand("Rain"), mock(Audiences.class));
    private final FormatService format = new FormatService(chat, new Identities(mock(Database.class)),
            null, ChatSettings.DEFAULTS);
    private final ChatHistoryService history = mock(ChatHistoryService.class);
    private final ChatQualityService quality = mock(ChatQualityService.class);
    private final MentionService mentions = mock(MentionService.class);
    private final FreezeService freeze = new FreezeService();
    private final PrivateChatService privateChat = new PrivateChatService();

    private final ChatListener listener = new ChatListener(new ChatServices(null, server, null, null,
            messages, chat, chat.brand(), () -> ChatSettings.DEFAULTS, format, mentions, quality, freeze,
            history, null, privateChat));

    private final Player alice = player("Alice");
    private final Player bob = player("Bob");
    private final Player eve = player("Eve");

    ChatListenerPrivateTest() {
        when(messages.raw("chat.private.line")).thenReturn("<dark_purple>[Private]</dark_purple> <line>");
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
    @DisplayName("a private line is cancelled, reaches every member, and nobody else")
    void reachesOnlyMembers() {
        privateChat.add(alice.getUniqueId(), bob.getUniqueId());
        AsyncChatEvent event = saying(alice, "meet at the portal");

        listener.onChat(event);

        verify(event).setCancelled(true);
        ArgumentCaptor<Component> toBob = ArgumentCaptor.forClass(Component.class);
        verify(bob).sendMessage(toBob.capture());
        assertThat(PLAIN.serialize(toBob.getValue())).isEqualTo("[Private] Alice: meet at the portal");
        verify(alice).sendMessage(any(Component.class));
        verify(eve, never()).sendMessage(any(Component.class));
        verify(event, never()).renderer(any());
    }

    @Test
    @DisplayName("a private line is never written into /chathistory, which everybody can read")
    void notInHistory() {
        privateChat.goPrivate(alice.getUniqueId());

        listener.onChat(saying(alice, "secret"));

        verifyNoInteractions(history);
        verifyNoInteractions(quality);
    }

    @Test
    @DisplayName("a public freeze does not silence a private chat — nobody in public reads it anyway")
    void freezeDoesNotApply() {
        freeze.freeze();
        privateChat.add(alice.getUniqueId(), bob.getUniqueId());

        listener.onChat(saying(alice, "still here"));

        verify(bob).sendMessage(any(Component.class));
    }

    @Test
    @DisplayName("a member who switched to public talks in public again — the line is not taken")
    void publicAgain() {
        privateChat.add(alice.getUniqueId(), bob.getUniqueId());
        privateChat.goPublic(bob.getUniqueId());
        when(quality.check(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(de.raindancer.core.platform.rule.Verdict.allowed());
        AsyncChatEvent event = saying(bob, "hello everyone");

        listener.onChat(event);

        verify(event, never()).setCancelled(true);
        verify(event).renderer(any());
        verify(alice, never()).sendMessage(any(Component.class));
    }

    @Test
    @DisplayName("the owner going offline closes the chat and tells whoever is left")
    void ownerQuits() {
        privateChat.add(alice.getUniqueId(), bob.getUniqueId());

        listener.onQuit(new org.bukkit.event.player.PlayerQuitEvent(alice, Component.empty(),
                org.bukkit.event.player.PlayerQuitEvent.QuitReason.DISCONNECTED));

        assertThat(privateChat.isTalkingPrivately(bob.getUniqueId())).isFalse();
        verify(messages).send(bob, "chat.private.ended-offline", "player", "Alice");
    }
}
