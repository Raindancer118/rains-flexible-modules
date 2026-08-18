package de.raindancer.modules.chat.service;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.ui.chat.Audiences;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.identity.Identities;
import de.raindancer.modules.chat.ChatSettings;
import de.raindancer.modules.chat.model.ChatStyle;
import de.raindancer.modules.chat.store.ChatStyleStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FormatServiceTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @TempDir
    static Path tempDir;

    private final Chat chat = new Chat(new Brand("Rain"), mock(Audiences.class));
    private final Identities identities = new Identities(mock(Database.class));
    private final ChatStyleService styles = new ChatStyleService(new ChatStyleStore(tempDir));
    private final FormatService service = new FormatService(chat, identities, styles, ChatSettings.DEFAULTS);

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
                new ChatSettings("<name> » <message>", true, true, NamedTextColor.WHITE, NamedTextColor.WHITE, false, true, true, 70, 8, true, 0, 0, true, 200, true);
        FormatService withCustomFormat = new FormatService(chat, identities, styles, custom);

        Component rendered = withCustomFormat.render(player("Tom"), "hello there", List.of());

        assertThat(PLAIN.serialize(rendered)).isEqualTo("Tom » hello there");
    }

    @Nested
    @DisplayName("default colours and brackets")
    class DefaultColoursAndBrackets {

        @Test
        @DisplayName("the default message colour applies when nobody chose their own")
        void defaultMessageColourApplies() {
            ChatSettings coloured = new ChatSettings("<name>: <message>", true, true,
                    NamedTextColor.AQUA, NamedTextColor.WHITE, false, true, true, 70, 8, true, 0, 0,
                    true, 200, true);
            FormatService withColour = new FormatService(chat, identities, styles, coloured);

            Component rendered = withColour.render(player("Tom"), "hello there", List.of());

            assertThat(findChild(rendered,
                    child -> child.color() == NamedTextColor.AQUA
                            && PLAIN.serialize(child).contains("hello there")))
                    .as("the message text should carry the default colour")
                    .isTrue();
        }

        @Test
        @DisplayName("the default message colour never overrides a colour the sender chose")
        void defaultMessageColourYieldsToPersonalStyle() {
            ChatSettings coloured = new ChatSettings("<name>: <message>", true, true,
                    NamedTextColor.AQUA, NamedTextColor.WHITE, false, true, true, 70, 8, true, 0, 0,
                    true, 200, true);
            FormatService withColour = new FormatService(chat, identities, styles, coloured);
            Player tom = player("Tom");
            styles.set(tom.getUniqueId(), ChatStyle.DEFAULT.withColor(NamedTextColor.GOLD));

            Component rendered = withColour.render(tom, "hello there", List.of());

            assertThat(findChild(rendered, child -> child.color() == NamedTextColor.AQUA)).isFalse();
            assertThat(findChild(rendered,
                    child -> child.color() == NamedTextColor.GOLD
                            && PLAIN.serialize(child).contains("hello there")))
                    .isTrue();
        }

        @Test
        @DisplayName("the default name colour applies when nothing else already coloured the name")
        void defaultNameColourApplies() {
            ChatSettings coloured = new ChatSettings("<name>: <message>", true, true,
                    NamedTextColor.WHITE, NamedTextColor.GREEN, false, true, true, 70, 8, true, 0, 0,
                    true, 200, true);
            FormatService withColour = new FormatService(chat, identities, styles, coloured);

            Component rendered = withColour.render(player("Tom"), "hello there", List.of());

            assertThat(findChild(rendered,
                    child -> child.color() == NamedTextColor.GREEN
                            && PLAIN.serialize(child).contains("Tom")))
                    .as("the name should carry the default name colour")
                    .isTrue();
        }

        @Test
        @DisplayName("brackets go around the name when the setting is on")
        void bracketsWrapTheName() {
            ChatSettings bracketed = new ChatSettings("<name>: <message>", true, true,
                    NamedTextColor.WHITE, NamedTextColor.WHITE, true, true, true, 70, 8, true, 0, 0,
                    true, 200, true);
            FormatService withBrackets = new FormatService(chat, identities, styles, bracketed);

            Component rendered = withBrackets.render(player("Tom"), "hello there", List.of());

            assertThat(PLAIN.serialize(rendered)).isEqualTo("<Tom>: hello there");
        }

        @Test
        @DisplayName("no brackets by default")
        void noBracketsByDefault() {
            Component rendered = service.render(player("Tom"), "hello there", List.of());

            assertThat(PLAIN.serialize(rendered)).doesNotContain("<Tom>");
        }
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
                    new ChatSettings("<name>: <message>", false, false, NamedTextColor.WHITE, NamedTextColor.WHITE, false, true, true, 70, 8, true, 0, 0, true, 200, true);
            FormatService withoutLinks = new FormatService(chat, identities, styles, noLinks);

            Component rendered =
                    withoutLinks.render(player("Tom"), "see https://example.com now", List.of());

            assertThat(findChild(rendered, child -> child.clickEvent() != null)).isFalse();
        }
    }

    @Nested
    @DisplayName("a personal chat style")
    class PersonalStyle {

        @Test
        @DisplayName("colours the plain part of the message")
        void coloursPlainText() {
            Player tom = player("Tom");
            styles.set(tom.getUniqueId(), ChatStyle.DEFAULT.withColor(NamedTextColor.GOLD));

            Component rendered = service.render(tom, "hello there", List.of());

            assertThat(PLAIN.serialize(rendered)).isEqualTo("Tom: hello there");
            assertThat(findChild(rendered,
                    child -> child.color() == NamedTextColor.GOLD
                            && PLAIN.serialize(child).contains("hello there")))
                    .as("the message text should carry the chosen colour")
                    .isTrue();
        }

        @Test
        @DisplayName("a highlighted @-mention keeps its own colour rather than the sender's")
        void mentionColourWinsOverSenderColour() {
            Player tom = player("Tom");
            Player alex = player("Alex");
            styles.set(tom.getUniqueId(), ChatStyle.DEFAULT.withColor(NamedTextColor.GOLD));

            Component rendered = service.render(tom, "hey @Alex look", List.of(alex));

            assertThat(findChild(rendered, child -> child.decoration(TextDecoration.BOLD)
                    == TextDecoration.State.TRUE && child.color() == NamedTextColor.YELLOW))
                    .as("the mention should still be yellow and bold, not the sender's gold")
                    .isTrue();
        }

        @Test
        @DisplayName("nobody has chosen anything by default, so no chosen colour appears")
        void defaultsToNoStyle() {
            Component rendered = service.render(player("Tom"), "hello there", List.of());

            assertThat(findChild(rendered, child -> child.color() == NamedTextColor.GOLD))
                    .as("nothing here chose gold, so it should not appear anywhere")
                    .isFalse();
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
