package de.raindancer.modules.chat.util;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LinksTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @Test
    @DisplayName("plain text with no link is unchanged")
    void noLink() {
        String rendered = PLAIN.serialize(Links.linkify("just talking, nothing here"));

        assertThat(rendered).isEqualTo("just talking, nothing here");
    }

    @Test
    @DisplayName("a link in the middle of a sentence keeps the words around it")
    void linkInSentence() {
        String rendered =
                PLAIN.serialize(Links.linkify("check out https://example.com it is great"));

        assertThat(rendered).isEqualTo("check out https://example.com it is great");
    }

    @Test
    @DisplayName("more than one link in the same line are both kept")
    void twoLinks() {
        String rendered = PLAIN.serialize(
                Links.linkify("https://one.example and https://two.example"));

        assertThat(rendered).isEqualTo("https://one.example and https://two.example");
    }

    @Test
    @DisplayName("blank text is returned as it is")
    void blankText() {
        assertThat(PLAIN.serialize(Links.linkify(""))).isEmpty();
    }

    @Test
    @DisplayName("null text is treated as empty")
    void nullText() {
        assertThat(PLAIN.serialize(Links.linkify(null))).isEmpty();
    }

    @Test
    @DisplayName("the link carries a click-to-open event")
    void linkHasClickEvent() {
        var component = Links.linkify("go to https://example.com now");

        boolean hasClick = component.children().stream()
                .anyMatch(child -> child.clickEvent() != null);
        assertThat(hasClick).isTrue();
    }
}
