package de.raindancer.modules.cosmetics;

import de.raindancer.core.ui.prompt.Parsed;
import de.raindancer.core.ui.text.NameStyle;
import de.raindancer.modules.cosmetics.model.Catalogue;
import de.raindancer.modules.cosmetics.model.PaletteColour;
import de.raindancer.modules.cosmetics.rules.TypedStyleRule;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code /cosmetics name set <words…>} — colours in order, decorations anywhere. */
class TypedStyleRuleTest {

    private static final TextColor LIGHT_BLUE = TextColor.fromHexString("#3ab3da");

    private static final Catalogue CATALOGUE = new Catalogue(
            List.of(new PaletteColour("light blue", LIGHT_BLUE, Material.LIGHT_BLUE_DYE)), List.of());

    private final TypedStyleRule rule = new TypedStyleRule();

    @Test
    @DisplayName("palette names, chat colours and hex codes all read, in the order typed")
    void coloursInOrder() {
        Parsed<NameStyle> read = rule.read(List.of("light_blue", "gold", "#8e2de2", "bold"), CATALOGUE);

        assertThat(read.isOk()).isTrue();
        assertThat(read.value().colours()).containsExactly(LIGHT_BLUE, NamedTextColor.GOLD,
                TextColor.fromHexString("#8e2de2"));
        assertThat(read.value().decorations()).containsExactly(TextDecoration.BOLD);
    }

    @Test
    @DisplayName("the palette wins over a chat colour of the same name")
    void paletteFirst() {
        Catalogue withRed = new Catalogue(List.of(new PaletteColour("red",
                TextColor.fromHexString("#b02e26"), Material.RED_DYE)), List.of());
        assertThat(rule.read(List.of("red"), withRed).value().colours())
                .containsExactly(TextColor.fromHexString("#b02e26"));
    }

    @Test
    @DisplayName("a word that is neither is named back, rather than skipped")
    void unknownWordIsNamed() {
        Parsed<NameStyle> read = rule.read(List.of("gold", "sparkly"), CATALOGUE);
        assertThat(read.isOk()).isFalse();
        assertThat(read.problem()).contains("sparkly");
    }

    @Test
    @DisplayName("nothing typed is a problem, not an empty style")
    void nothingTyped() {
        assertThat(rule.read(List.of(), CATALOGUE).isOk()).isFalse();
    }
}
