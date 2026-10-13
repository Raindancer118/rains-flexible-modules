package de.raindancer.modules.ranks.rules;

import de.raindancer.core.ui.text.NameStyle;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TagStyleRuleTest {

    private final TagStyleRule rule = new TagStyleRule();

    @Test
    @DisplayName("one colour and a decoration are read back as they were written")
    void colourAndBold() {
        NameStyle style = rule.styleOf("<red><bold>[OWNER]</bold></red>");

        assertThat(style.colours()).containsExactly(NamedTextColor.RED);
        assertThat(style.decorations()).containsExactly(TextDecoration.BOLD);
    }

    @Test
    @DisplayName("a gradient is read as its first and last colour")
    void gradient() {
        NameStyle style = rule.styleOf("<gradient:#ff0000:#0000ff>[VIP]</gradient>");

        assertThat(style.colours()).hasSize(2);
        assertThat(style.colours().getFirst()).isEqualTo(TextColor.fromHexString("#ff0000"));
        assertThat(style.colours().getLast()).isEqualTo(TextColor.fromHexString("#0000ff"));
        assertThat(style.isGradient()).isTrue();
    }

    @Test
    @DisplayName("a tag with no colour, or one that cannot be read, is plain")
    void plain() {
        assertThat(rule.styleOf("[HELPER]").isEmpty()).isTrue();
        assertThat(rule.styleOf("").isEmpty()).isTrue();
        assertThat(rule.styleOf(null).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("the letters of a tag are what is left when the markup is taken away")
    void letters() {
        assertThat(rule.lettersOf("<gold><bold>[MOD]</bold></gold>")).isEqualTo("[MOD]");
        assertThat(rule.lettersOf(null)).isEmpty();
    }
}
