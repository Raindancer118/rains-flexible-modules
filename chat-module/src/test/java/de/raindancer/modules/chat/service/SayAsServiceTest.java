package de.raindancer.modules.chat.service;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.moderation.audit.Audit;
import de.raindancer.core.moderation.audit.AuditEntry;
import de.raindancer.core.moderation.chatlog.ChatLog;
import de.raindancer.core.moderation.vanish.Vanish;
import de.raindancer.core.moderation.vanish.VanishSink;
import de.raindancer.core.ui.chat.Audiences;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.identity.Identities;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.chat.ChatSettings;
import de.raindancer.modules.chat.store.ChatHistoryStore;
import de.raindancer.modules.chat.store.ChatStyleStore;
import de.raindancer.modules.chat.store.PanelFeed;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** /chat say from the console: a public line in somebody's name, looking exactly like one they typed. */
class SayAsServiceTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @TempDir
    Path dir;

    private final Server server = mock(Server.class);
    private final ConsoleCommandSender console = mock(ConsoleCommandSender.class);
    private final Player alex = mock(Player.class);
    private final OfflinePlayer tom = mock(OfflinePlayer.class);
    private final UUID tomId = UUID.randomUUID();
    private final ChatLog chatLog = mock(ChatLog.class);
    private final Audit audit = mock(Audit.class);
    private final Set<UUID> muted = new HashSet<>();

    private SayAsService service(PanelFeed feed, ChatHistoryStore history) {
        Chat chat = new Chat(new Brand("Rain"), mock(Audiences.class));
        FormatService format = new FormatService(chat, new Identities(mock(Database.class)),
                new ChatStyleService(new ChatStyleStore(dir)), ChatSettings.DEFAULTS);
        MentionService mentions = new MentionService(server, new Vanish(mock(VanishSink.class)),
                mock(Messages.class), ChatSettings.DEFAULTS);
        return new SayAsService(server, format, mentions, new ChatHistoryService(history, ChatSettings.DEFAULTS),
                feed, muted::contains, chatLog, audit, () -> 5000L);
    }

    SayAsServiceTest() {
        when(tom.getUniqueId()).thenReturn(tomId);
        when(tom.getName()).thenReturn("Raindancer118");
        when(alex.getUniqueId()).thenReturn(UUID.randomUUID());
        when(alex.getName()).thenReturn("Alex");
        doReturn(List.of(alex)).when(server).getOnlinePlayers();
        when(server.getConsoleSender()).thenReturn(console);
    }

    @Test
    @DisplayName("everybody online and the console see the line in the chat format, as if they had typed it")
    void saysIt() throws Exception {
        PanelFeed feed = new PanelFeed(dir, 100);
        ChatHistoryStore history = new ChatHistoryStore(dir);

        assertThat(service(feed, history).say(tom, "hello from the panel")).isEqualTo(SayAsService.Said.SAID);

        ArgumentCaptor<Component> shown = ArgumentCaptor.forClass(Component.class);
        verify(alex).sendMessage(shown.capture());
        assertThat(PLAIN.serialize(shown.getValue())).isEqualTo("Raindancer118: hello from the panel");
        verify(console).sendMessage(shown.getValue());
        assertThat(history.lastLines(1)).singleElement()
                .satisfies(line -> assertThat(line.text()).isEqualTo("hello from the panel"));
        verify(chatLog).record(tomId, "Raindancer118", SayAsService.CHANNEL, "hello from the panel");
        verify(audit).record(any(AuditEntry.Builder.class));
        feed.flush();
        String written = Files.readString(dir.resolve(PanelFeed.FILE));
        assertThat(written).contains("\"panel\":true").contains("hello from the panel");
    }

    @Test
    @DisplayName("a muted player stays muted, even from the console")
    void refusesMuted() {
        muted.add(tomId);

        assertThat(service(new PanelFeed(dir, 100), new ChatHistoryStore(dir)).say(tom, "let me out"))
                .isEqualTo(SayAsService.Said.MUTED);

        verify(alex, never()).sendMessage(any(Component.class));
        verify(chatLog, never()).record(any(), any(), any(), any());
    }

    @Test
    @DisplayName("control characters are taken out, a line is cut to chat's own limit, and an empty one is refused")
    void cleansText() {
        SayAsService service = service(new PanelFeed(dir, 100), new ChatHistoryStore(dir));

        assertThat(service.say(tom, " \u0007 \t")).isEqualTo(SayAsService.Said.EMPTY);
        assertThat(service.say(tom, "a\u0000b" + "x".repeat(400))).isEqualTo(SayAsService.Said.SAID);

        verify(chatLog).record(eq(tomId), eq("Raindancer118"), eq(SayAsService.CHANNEL),
                eq("ab" + "x".repeat(254)));
    }
}
