package de.raindancer.modules.names;

import de.raindancer.modules.names.model.NameStyle;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The style itself: how it combines, and how it survives being written onto an item and read back.
 * <p>
 * The round trip is the one that matters. A style lives in an item's persistent data between two
 * server sessions, so a change to the encoding that nothing checks would silently turn every dyed
 * name tag on the server back into a plain one.
 */
class NameStyleTest {

    @Test
    @DisplayName("a style survives being written and read back")
    void roundTrip() {
        NameStyle original = new NameStyle(
                List.of(NamedTextColor.DARK_RED, TextColor.fromHexString("#835432")),
                Set.of(TextDecoration.BOLD, TextDecoration.ITALIC));

        NameStyle read = NameStyle.decode(original.encodeColours(), original.encodeDecorations());

        assertThat(read).isEqualTo(original);
    }

    @Test
    @DisplayName("two styles built by different routes are equal")
    void equalityDoesNotDependOnOrder() {
        NameStyle one = new NameStyle(List.of(NamedTextColor.RED), Set.of())
                .toggle(TextDecoration.ITALIC).toggle(TextDecoration.BOLD);
        NameStyle other = new NameStyle(List.of(NamedTextColor.RED),
                Set.of(TextDecoration.BOLD, TextDecoration.ITALIC));

        // Both the equality and the encoding: a set with an unstable iteration order would make the
        // second of these pass and the first fail, or the other way round, depending on the day.
        assertThat(one).isEqualTo(other);
        assertThat(one.encodeDecorations()).isEqualTo(other.encodeDecorations());
    }

    @Test
    @DisplayName("data written by something else is read as far as it makes sense")
    void unreadableDataIsSkippedNotFatal() {
        // Off an item edited with an NBT tool, or written by a future version. Refusing the whole
        // style would leave the player holding a name tag that cannot be used at all.
        NameStyle read = NameStyle.decode("#ff0000,not-a-colour,", "bold,sideways");

        assertThat(read.colours()).containsExactly(TextColor.fromHexString("#ff0000"));
        assertThat(read.decorations()).containsExactly(TextDecoration.BOLD);
    }

    @Test
    @DisplayName("nothing at all decodes to nothing at all")
    void emptyDecodesToNone() {
        assertThat(NameStyle.decode("", "")).isEqualTo(NameStyle.NONE);
        assertThat(NameStyle.decode(null, null)).isEqualTo(NameStyle.NONE);
        assertThat(NameStyle.NONE.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("dyeing replaces the colour rather than adding a second stop")
    void colourIsReplaced() {
        NameStyle red = NameStyle.NONE.withColour(NamedTextColor.RED);
        assertThat(red.withColour(NamedTextColor.BLUE).colours()).containsExactly(NamedTextColor.BLUE);
    }

    @Test
    @DisplayName("a decoration flips off again, and takes the colour with it untouched")
    void toggling() {
        NameStyle red = NameStyle.NONE.withColour(NamedTextColor.RED);
        NameStyle bold = red.toggle(TextDecoration.BOLD);

        assertThat(bold.decorations()).containsExactly(TextDecoration.BOLD);
        assertThat(bold.toggle(TextDecoration.BOLD)).isEqualTo(red);
    }

    @Test
    @DisplayName("merging lays the colours end to end and pools the decorations")
    void merging() {
        NameStyle merged = NameStyle.merge(List.of(
                new NameStyle(List.of(NamedTextColor.RED), Set.of(TextDecoration.BOLD)),
                new NameStyle(List.of(NamedTextColor.BLUE), Set.of(TextDecoration.ITALIC))));

        assertThat(merged.colours()).containsExactly(NamedTextColor.RED, NamedTextColor.BLUE);
        assertThat(merged.decorations())
                .containsExactlyInAnyOrder(TextDecoration.BOLD, TextDecoration.ITALIC);
    }

    @Test
    @DisplayName("a style cannot be changed once it is on an item")
    void stylesAreImmutable() {
        java.util.List<TextColor> mutable = new java.util.ArrayList<>(List.of(NamedTextColor.RED));
        NameStyle style = new NameStyle(mutable, Set.of());
        mutable.add(NamedTextColor.BLUE);

        assertThat(style.colours()).containsExactly(NamedTextColor.RED);
        assertThat(style.colours()).isUnmodifiable();
    }
}
