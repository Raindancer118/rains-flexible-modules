package de.raindancer.modules.cosmetics;

import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.core.ui.text.NameStyle;
import de.raindancer.modules.cosmetics.model.Catalogue;
import de.raindancer.modules.cosmetics.model.Grants;
import de.raindancer.modules.cosmetics.model.PaletteColour;
import de.raindancer.modules.cosmetics.model.Preset;
import de.raindancer.modules.cosmetics.rules.NameStyleRule;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may wear which name. The whole module's permission model is this one rule, so a screen greying a
 * button, the command and the join check that takes a lost perk away can never disagree.
 */
class NameStyleRuleTest {

    private static final TextColor PINK = TextColor.fromHexString("#f38baa");
    private static final TextColor BLUE = TextColor.fromHexString("#3c44aa");
    private static final TextColor OFF_PALETTE = TextColor.fromHexString("#123456");

    private static final Preset SUNSET = new Preset("sunset", "Sunset",
            new NameStyle(List.of(TextColor.fromHexString("#ff5f6d"), TextColor.fromHexString("#ffc371")),
                    Set.of()), false);
    private static final Preset RAINBOW = new Preset("rainbow", "Rainbow",
            new NameStyle(List.of(NamedTextColor.RED, NamedTextColor.YELLOW, NamedTextColor.AQUA),
                    Set.of(TextDecoration.OBFUSCATED)), true);

    private static final Catalogue CATALOGUE = new Catalogue(
            List.of(new PaletteColour("pink", PINK, Material.PINK_DYE),
                    new PaletteColour("blue", BLUE, Material.BLUE_DYE)),
            List.of(SUNSET, RAINBOW));

    private static final Set<TextDecoration> READABLE = EnumSet.of(TextDecoration.BOLD,
            TextDecoration.ITALIC, TextDecoration.UNDERLINED, TextDecoration.STRIKETHROUGH);

    /** What an ordinary player gets on a server nobody has configured. */
    private static final Grants EVERYBODY = new Grants(true, true, true, READABLE, Set.of());

    private final NameStyleRule rule = new NameStyleRule();

    private Verdict judge(NameStyle style, Grants grants) {
        return rule.judge(style, grants, CATALOGUE, 8);
    }

    @Test
    @DisplayName("a preset the server sells is worn only by whoever bought it, and cannot be rebuilt by hand")
    void soldPreset() {
        Grants notBought = new Grants(true, true, true, true, READABLE, Set.of(), Set.of("sunset"));
        Grants bought = new Grants(true, true, true, true, READABLE, Set.of("sunset"), Set.of("sunset"));

        Verdict refused = judge(SUNSET.style(), notBought);

        assertThat(refused.reason()).isEqualTo("cosmetics.refused.sold-preset");
        assertThat(refused.detail()).isEqualTo("Sunset");
        assertThat(rule.mayUse(SUNSET, notBought)).isFalse();
        assertThat(judge(SUNSET.style(), bought).isAllowed()).isTrue();
        assertThat(rule.mayUse(SUNSET, bought)).isTrue();
        assertThat(rule.mayUse(SUNSET, EVERYBODY)).as("not sold: public as ever").isTrue();
    }

    @Test
    @DisplayName("nothing at all is always allowed — taking a style off must never be refused")
    void nothingIsAllowed() {
        assertThat(judge(NameStyle.NONE, Grants.NOTHING).isAllowed()).isTrue();
    }

    @Test
    @DisplayName("a plain player may wear a palette colour, a gradient and the readable decorations")
    void ordinaryPlayer() {
        assertThat(judge(NameStyle.NONE.withColour(PINK), EVERYBODY).isAllowed()).isTrue();
        assertThat(judge(NameStyle.NONE.withStop(PINK).withStop(BLUE)
                .with(TextDecoration.BOLD, true), EVERYBODY).isAllowed()).isTrue();
    }

    @Test
    @DisplayName("each missing permission refuses with its own reason")
    void eachRefusalSaysWhy() {
        assertThat(judge(NameStyle.NONE.withColour(PINK),
                new Grants(false, true, true, READABLE, Set.of())).reason())
                .isEqualTo("cosmetics.refused.colour");
        assertThat(judge(NameStyle.NONE.withStop(PINK).withStop(BLUE),
                new Grants(true, false, true, READABLE, Set.of())).reason())
                .isEqualTo("cosmetics.refused.gradient");
        assertThat(judge(NameStyle.NONE.withColour(OFF_PALETTE),
                new Grants(true, true, false, READABLE, Set.of())).reason())
                .isEqualTo("cosmetics.refused.any-colour");
        Verdict obfuscated = judge(NameStyle.NONE.with(TextDecoration.OBFUSCATED, true), EVERYBODY);
        assertThat(obfuscated.reason()).isEqualTo("cosmetics.refused.decoration");
        assertThat(obfuscated.detail()).isEqualTo("obfuscated");
    }

    @Test
    @DisplayName("a palette colour does not need the any-colour permission, however it was typed")
    void paletteColoursAreFree() {
        Grants paletteOnly = new Grants(true, true, false, READABLE, Set.of());
        assertThat(judge(NameStyle.NONE.withColour(TextColor.fromHexString("#F38BAA")), paletteOnly)
                .isAllowed()).isTrue();
    }

    @Test
    @DisplayName("more stops than the server allows are refused, and the limit is named")
    void tooManyStops() {
        NameStyle three = NameStyle.NONE.withStop(PINK).withStop(BLUE).withStop(PINK);
        Verdict verdict = rule.judge(three, EVERYBODY, CATALOGUE, 2);
        assertThat(verdict.reason()).isEqualTo("cosmetics.refused.too-many-stops");
        assertThat(verdict.detail()).isEqualTo("2");
    }

    @Test
    @DisplayName("a public preset is allowed even when its parts would not be")
    void publicPresetsNeedOnlyThemselves() {
        // A server that hands out gradients only as presets turns off the gradient node and keeps the
        // presets: the preset is the permission, not the sum of its parts.
        Grants presetsOnly = new Grants(false, false, false, Set.of(), Set.of());
        assertThat(judge(SUNSET.style(), presetsOnly).isAllowed()).isTrue();
    }

    @Test
    @DisplayName("a restricted preset needs its own permission, and then nothing else")
    void restrictedPresets() {
        assertThat(judge(RAINBOW.style(), EVERYBODY).isRefused()).isTrue();
        Grants granted = new Grants(false, false, false, Set.of(), Set.of("rainbow"));
        assertThat(judge(RAINBOW.style(), granted).isAllowed()).isTrue();
        assertThat(rule.mayUse(RAINBOW, granted)).isTrue();
        assertThat(rule.mayUse(RAINBOW, EVERYBODY)).isFalse();
        assertThat(rule.mayUse(SUNSET, Grants.NOTHING)).isTrue();
    }

    @Test
    @DisplayName("the preset's permission node is derived from its id, never stored")
    void presetNodes() {
        assertThat(RAINBOW.permission()).isEqualTo("rainscosmetics.preset.rainbow");
    }

    @Test
    @DisplayName("the catalogue finds the preset a style came from, if any")
    void presetLookup() {
        assertThat(CATALOGUE.presetMatching(SUNSET.style())).contains(SUNSET);
        assertThat(CATALOGUE.presetMatching(NameStyle.NONE.withColour(PINK))).isEmpty();
        assertThat(CATALOGUE.preset("SUNSET")).contains(SUNSET);
        assertThat(CATALOGUE.colourNamed("Pink")).contains(CATALOGUE.palette().getFirst());
        assertThat(CATALOGUE.nameOf(BLUE)).isEqualTo("blue");
        assertThat(CATALOGUE.nameOf(OFF_PALETTE)).isEqualToIgnoringCase("#123456");
    }

    @Test
    @DisplayName("a flowing gradient needs its own permission, unless it is a preset")
    void animatedNeedsItsNode() {
        NameStyle flowing = NameStyle.NONE.withStop(PINK).withStop(BLUE).animated(true);
        Grants still = new Grants(true, true, true, false, READABLE, Set.of());
        assertThat(judge(flowing, still).reason()).isEqualTo("cosmetics.refused.animated");
        assertThat(judge(flowing, EVERYBODY).isAllowed()).isTrue();
    }
}
