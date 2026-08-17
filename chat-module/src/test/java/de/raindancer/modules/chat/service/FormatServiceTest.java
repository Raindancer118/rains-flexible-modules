package de.raindancer.modules.chat.service;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.ui.chat.Audiences;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.identity.Identities;
import de.raindancer.modules.chat.ChatSettings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FormatServiceTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final Chat chat = new Chat(new Brand("Rain"), mock(Audiences.class));
    private final Identities identities = new Identities(mock(Database.class));
    private final FormatService service = new FormatService(chat, identities, ChatSettings.DEFAULTS);

    private Player player(String name) {
        Player who = mock(Player.class);
        when(who.getUniqueId()).thenReturn(UUID.randomUUID());
        when(who.getName()).thenReturn(name);
        return who;
    }

    @Test
    @DisplayName("renders the default format as name, colon, message")
    void rendersDefaultFormat() {
        Component rendered = service.render(player("Tom"), "hello there", List.of());

        assertThat(PLAIN.serialize(rendered)).isEqualTo("Tom: hello there");
    }

    @Test
    @DisplayName("an owner's own template is used instead")
    void usesConfiguredFormat() {
        ChatSettings custom =
                new ChatSettings("<name> » <message>", true, true, true, true, 70, 8, true, 0, 0, true, 200, true);
        FormatService withCustomFormat = new FormatService(chat, identities, custom);

        Component rendered = withCustomFormat.render(player("Tom"), "hello there", List.of());

        assertThat(PLAIN.serialize(rendered)).isEqualTo("Tom » hello there");
    }

    @Nested
    @DisplayName("mentions")
    class Mentions {

        @Test
        @DisplayName("a mentioned name is highlighted without changing the text")
        void highlightsMention() {
            Player alex = player("Alex");

            Component rendered = service.render(player("Tom"), "hey @Alex look", List.of(alex));

            assertThat(PLAIN.serialize(rendered)).isEqualTo("Tom: hey @Alex look");
            assertThat(findChild(rendered, child -> child.decoration(TextDecoration.BOLD)
                    == TextDecoration.State.TRUE && child.color() == NamedTextColor.YELLOW))
                    .as("the @Alex token should be bold and yellow")
                    .isTrue();
        }

        @Test
        @DisplayName("a token that is not in the mentioned list stays plain")
        void leavesUnknownTokenPlain() {
            Component rendered = service.render(player("Tom"), "hey @Nobody", List.of());

            assertThat(PLAIN.serialize(rendered)).isEqualTo("Tom: hey @Nobody");
            assertThat(findChild(rendered, child -> child.color() == NamedTextColor.YELLOW)).isFalse();
        }
    }

    @Nested
    @DisplayName("links")
    class LinksInMessages {

        @Test
        @DisplayName("a URL in the message is styled but the text is unchanged")
        void linkifiesUrl() {
            Component rendered =
                    service.render(player("Tom"), "see https://example.com now", List.of());

            assertThat(PLAIN.serialize(rendered)).isEqualTo("Tom: see https://example.com now");
            assertThat(findChild(rendered, child -> child.clickEvent() != null)).isTrue();
        }

        @Test
        @DisplayName("links are left plain when the setting is off")
        void skipsLinkifyingWhenDisabled() {
            ChatSettings noLinks =
                    new ChatSettings("<name>: <message>", false, false, true, true, 70, 8, true, 0, 0, true, 200, true);
            FormatService withoutLinks = new FormatService(chat, identities, noLinks);

            Component rendered =
                    withoutLinks.render(player("Tom"), "see https://example.com now", List.of());

            assertThat(findChild(rendered, child -> child.clickEvent() != null)).isFalse();
        }
    }

    private static boolean findChild(Component root, java.util.function.Predicate<Component> test) {
        if (test.test(root)) {
            return true;
        }
        for (Component child : root.children()) {
            if (findChild(child, test)) {
                return true;
            }
        }
        return false;
    }
}
