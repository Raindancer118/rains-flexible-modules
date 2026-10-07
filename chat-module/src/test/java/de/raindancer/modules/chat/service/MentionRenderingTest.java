package de.raindancer.modules.chat.service;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.moderation.vanish.Vanish;
import de.raindancer.core.moderation.vanish.VanishSink;
import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.ui.chat.Audiences;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.identity.Identities;
import de.raindancer.core.ui.identity.Nicknames;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.ui.text.NameStyle;
import de.raindancer.modules.chat.ChatSettings;
import de.raindancer.modules.chat.model.Mention;
import de.raindancer.modules.chat.store.ChatStyleStore;
import de.raindancer.modules.chat.store.MentionInbox;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Mentions by name or nickname, online or offline, drawn in the mentioned player's own name style.
 */
class MentionRenderingTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @TempDir
    Path directory;

    private final Server server = mock(Server.class);
    private final Vanish vanish = new Vanish(mock(VanishSink.class));
    private Database database;
    private Nicknames nicknames;
    private Identities identities;
    private MentionService mentions;
    private FormatService format;
    private MentionInbox inbox;
    private Player tom;
    private Player lilly;
    private OfflinePlayer ghost;

    @BeforeEach
    void setUp() {
        database = Database.open(directory.resolve("core.db"), CoreSchema.CORE, () -> false);
        nicknames = new Nicknames(database);
        identities = new Identities(database);
        PlayerTargets.useNicknames(nicknames);
        tom = player("Tom");
        lilly = player("lillyyxoxo");
        doReturn(List.of(tom, lilly)).when(server).getOnlinePlayers();
        ghost = mock(OfflinePlayer.class);
        UUID ghostId = UUID.nameUUIDFromBytes("ghost".getBytes());
        when(ghost.getUniqueId()).thenReturn(ghostId);
        when(ghost.getName()).thenReturn("OldGhost");
        when(server.getOfflinePlayerIfCached("OldGhost")).thenReturn(ghost);
        when(server.getOfflinePlayer(ghostId)).thenReturn(ghost);
        nicknames.remember(lilly.getUniqueId(), "Lilly Pad");
        inbox = new MentionInbox(directory);
        mentions = new MentionService(server, vanish, mock(Messages.class), ChatSettings.DEFAULTS, inbox);
        format = new FormatService(new Chat(new Brand("Rain"), mock(Audiences.class)), identities,
                new ChatStyleService(new ChatStyleStore(directory)), ChatSettings.DEFAULTS);
    }

    @AfterEach
    void tearDown() {
        PlayerTargets.useNicknames(null);
        database.close();
    }

    private Player player(String name) {
        Player who = mock(Player.class);
        UUID id = UUID.nameUUIDFromBytes(name.getBytes());
        when(who.getUniqueId()).thenReturn(id);
        when(who.getName()).thenReturn(name);
        when(who.isOnline()).thenReturn(true);
        when(server.getPlayerExact(name)).thenReturn(who);
        when(server.getPlayer(id)).thenReturn(who);
        return who;
    }

    @Test
    @DisplayName("a nickname mention is found where it stands, and pings its owner")
    void nicknameSpan() {
        List<Mention> found = mentions.find(tom, "hey @Lilly_Pad look");

        assertThat(found).singleElement().satisfies(mention -> {
            assertThat(mention.player()).isEqualTo(lilly.getUniqueId());
            assertThat(mention.start()).isEqualTo(4);
            assertThat(mention.end()).isEqualTo(14);
            assertThat(mention.reachable()).isTrue();
        });
    }

    @Test
    @DisplayName("an offline player is mentioned too — highlighted, not pinged, and told when they are back")
    void offline() {
        List<Mention> found = mentions.find(tom, "where is @OldGhost?");

        assertThat(found).singleElement().satisfies(mention -> assertThat(mention.reachable()).isFalse());
        mentions.notify(tom, "where is @OldGhost?", found);
        assertThat(inbox.waitingFor(ghost.getUniqueId())).singleElement()
                .satisfies(note -> assertThat(note.text()).isEqualTo("where is @OldGhost?"));
    }

    @Test
    @DisplayName("a hidden player is treated exactly like an offline one, so nothing gives them away")
    void hidden() {
        vanish.vanish(lilly.getUniqueId());

        List<Mention> found = mentions.find(tom, "@lillyyxoxo");

        assertThat(found).singleElement().satisfies(mention -> assertThat(mention.reachable()).isFalse());
    }

    @Test
    @DisplayName("the mention wears the mentioned player's own colours")
    void styled() {
        identities.setNameStyle(lilly.getUniqueId(), NameStyle.NONE.withColour(NamedTextColor.LIGHT_PURPLE));
        List<Mention> found = mentions.find(tom, "hey @Lilly_Pad look");

        Component rendered = format.renderLine(tom, "hey @Lilly_Pad look", found);

        assertThat(PLAIN.serialize(rendered)).isEqualTo("Tom: hey @Lilly_Pad look");
        assertThat(anyChild(rendered, child -> NamedTextColor.LIGHT_PURPLE.equals(child.color())
                && child.content().contains("@Lilly_Pad"))).isTrue();
    }

    @Test
    @DisplayName("without a style of their own, the mention keeps the familiar bold yellow")
    void fallback() {
        Component rendered = format.renderLine(tom, "hi @lillyyxoxo", mentions.find(tom, "hi @lillyyxoxo"));

        assertThat(anyChild(rendered, child -> NamedTextColor.YELLOW.equals(child.color()))).isTrue();
    }

    @Test
    @DisplayName("an inbox survives a restart and is emptied once delivered")
    void inboxPersists() {
        inbox.add(ghost.getUniqueId(), "Tom", "hello");
        inbox.save();

        MentionInbox reread = new MentionInbox(directory);
        reread.load();
        assertThat(reread.take(ghost.getUniqueId())).hasSize(1);
        assertThat(reread.take(ghost.getUniqueId())).isEmpty();
    }

    private static boolean anyChild(Component component, Predicate<TextComponent> test) {
        if (component instanceof TextComponent text && test.test(text)) {
            return true;
        }
        for (Component child : component.children()) {
            if (anyChild(child, test)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unused")
    private static final Set<TextColor> UNUSED = Set.of();
}
