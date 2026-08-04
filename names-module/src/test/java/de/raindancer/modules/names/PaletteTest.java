package de.raindancer.modules.names;

import de.raindancer.modules.names.model.NameStyle;
import de.raindancer.modules.names.model.Reagent;
import de.raindancer.modules.names.store.Palette;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The palette: what the shipped tables promise, and what a configured one is allowed to say.
 *
 * <p>The claim the config file makes in its own comments — "every dye gives the colour it actually is"
 * — is the sort of thing that is true when it is written and quietly stops being true two edits later.
 * It is checked here against {@link DyeColor} rather than against a second table, so there is nothing
 * for the answer to drift away from.
 */
class PaletteTest {

    @Test
    @DisplayName("a dye gives the colour Minecraft says that dye is")
    void dyesGiveTheirOwnColour() {
        // The bug this replaces: the table used to map dyes onto the sixteen chat colours so that all
        // sixteen were reachable, which made pink dye produce red. The first person to use the plugin
        // took pink dye and got a red name tag. Whatever else changes, that must not come back.
        Palette palette = Palette.defaults();
        for (DyeColor dye : DyeColor.values()) {
            Material item = Material.getMaterial(dye.name() + "_DYE");
            assertThat(item).as("%s has an item", dye).isNotNull();

            Reagent reagent = palette.reagentFor(item);
            assertThat(reagent).as("%s does something", item).isInstanceOf(Reagent.Colour.class);
            assertThat(((Reagent.Colour) reagent).colour().value())
                    .as("%s is its own colour", item)
                    .isEqualTo(dye.getColor().asRGB());
        }
    }

    @Test
    @DisplayName("pink dye is pink")
    void pinkIsPink() {
        Reagent pink = Palette.defaults().reagentFor(Material.PINK_DYE);
        assertThat(((Reagent.Colour) pink).colour().asHexString()).isEqualToIgnoringCase("#f38baa");
        assertThat(pink.describe()).isEqualTo("pink");
    }

    @Test
    @DisplayName("coal darkens and glowstone lightens, one step at a time")
    void shadesMoveTheColour() {
        Palette palette = Palette.defaults();
        NameStyle pink = NameStyle.NONE.withColour(
                ((Reagent.Colour) palette.reagentFor(Material.PINK_DYE)).colour());

        NameStyle darker = palette.reagentFor(Material.COAL).appliedTo(pink);
        NameStyle lighter = palette.reagentFor(Material.GLOWSTONE_DUST).appliedTo(pink);

        assertThat(darker.colours().getFirst().value()).isLessThan(pink.colours().getFirst().value());
        assertThat(lighter.colours().getFirst().value()).isGreaterThan(pink.colours().getFirst().value());
        // And it keeps going, so it is a dial rather than a single darker/lighter state.
        NameStyle twice = palette.reagentFor(Material.COAL).appliedTo(darker);
        assertThat(twice.colours().getFirst().value()).isLessThan(darker.colours().getFirst().value());
    }

    @Test
    @DisplayName("shading an undyed tag changes nothing, so no coal is spent")
    void shadingNothingIsANoOp() {
        assertThat(Palette.defaults().reagentFor(Material.COAL).appliedTo(NameStyle.NONE))
                .isEqualTo(NameStyle.NONE);
    }

    @Test
    @DisplayName("a shade moves every stop of a gradient, not just the first")
    void shadesApplyToTheWholeGradient() {
        NameStyle gradient = new NameStyle(
                List.of(TextColor.fromHexString("#f38baa"), TextColor.fromHexString("#3ab3da")),
                Set.of());
        NameStyle darker = Palette.defaults().reagentFor(Material.COAL).appliedTo(gradient);

        assertThat(darker.colours()).hasSize(2);
        for (int index = 0; index < 2; index++) {
            assertThat(darker.colours().get(index).value())
                    .isLessThan(gradient.colours().get(index).value());
        }
    }

    @Test
    @DisplayName("every dye in the game does something")
    void everyDyeIsUsed() {
        Palette palette = Palette.defaults();
        List<Material> unused = new ArrayList<>();
        for (Material material : Material.values()) {
            if (material.name().endsWith("_DYE") && palette.reagentFor(material) == null) {
                unused.add(material);
            }
        }
        assertThat(unused).as("dyes that colour nothing").isEmpty();
    }

    @Test
    @DisplayName("all five decorations have an item")
    void everyDecorationIsReachable() {
        Set<TextDecoration> reachable = new HashSet<>();
        Palette.defaults().reagents().values().forEach(reagent -> {
            if (reagent instanceof Reagent.Decoration decoration) {
                reachable.add(decoration.decoration());
            }
        });
        assertThat(reachable).containsExactlyInAnyOrder(TextDecoration.values());
    }

    @Test
    @DisplayName("the listing is colours, then decorations, then shades")
    void orderingIsStable() {
        List<Map.Entry<Material, Reagent>> ordered = Palette.defaults().ordered();

        // One ordering for the screen and the chat listing alike: two would be two answers to "what is
        // the third one along", which is how somebody is told to click the wrong button.
        int firstDecoration = indexOfFirst(ordered, Reagent.Decoration.class);
        int firstShade = indexOfFirst(ordered, Reagent.Shade.class);
        int lastColour = indexOfLast(ordered, Reagent.Colour.class);

        assertThat(lastColour).isLessThan(firstDecoration);
        assertThat(firstDecoration).isLessThan(firstShade);
        assertThat(ordered).hasSize(Palette.defaults().reagents().size());
    }

    private static int indexOfFirst(List<Map.Entry<Material, Reagent>> entries, Class<?> kind) {
        for (int index = 0; index < entries.size(); index++) {
            if (kind.isInstance(entries.get(index).getValue())) {
                return index;
            }
        }
        throw new AssertionError("no " + kind.getSimpleName() + " in the shipped palette");
    }

    private static int indexOfLast(List<Map.Entry<Material, Reagent>> entries, Class<?> kind) {
        for (int index = entries.size() - 1; index >= 0; index--) {
            if (kind.isInstance(entries.get(index).getValue())) {
                return index;
            }
        }
        throw new AssertionError("no " + kind.getSimpleName() + " in the shipped palette");
    }

    // ------------------------------------------------------------------ reading a config

    @Test
    @DisplayName("an item name is accepted however it is written")
    void materialNamesAreForgiving() {
        assertThat(Palette.material("RED_DYE")).isEqualTo(Material.RED_DYE);
        assertThat(Palette.material("red_dye")).isEqualTo(Material.RED_DYE);
        assertThat(Palette.material("minecraft:red_dye")).isEqualTo(Material.RED_DYE);
        assertThat(Palette.material(" Red_Dye ")).isEqualTo(Material.RED_DYE);
        assertThat(Palette.material("not_a_thing")).isNull();
        assertThat(Palette.material("AIR")).isNull();
    }

    @Test
    @DisplayName("a hex colour works as well as a named one")
    void hexColours() {
        assertThat(Palette.parseColourOrNull("#835432")).isNotNull();
        assertThat(Palette.parseColourOrNull("dark_red")).isEqualTo(NamedTextColor.DARK_RED);
        assertThat(Palette.parseColourOrNull("DARK_RED")).isEqualTo(NamedTextColor.DARK_RED);
        assertThat(Palette.parseColourOrNull("puce")).isNull();
        assertThat(Palette.parseColourOrNull("#nothex")).isNull();
    }

    @Test
    @DisplayName("one bad line costs that line, not the whole palette")
    void badLinesAreSkippedAndReported() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("colours.RED_DYE", "dark_red");
        config.set("colours.NOT_AN_ITEM", "blue");
        config.set("colours.STONE", "puce");
        config.set("decorations.IRON_INGOT", "bold");
        config.set("decorations.STICK", "sideways");

        List<String> warnings = new ArrayList<>();
        Palette palette = Palette.from(config, warnings::add);

        assertThat(palette.reagentFor(Material.RED_DYE)).isNotNull();
        assertThat(palette.reagentFor(Material.IRON_INGOT)).isNotNull();
        assertThat(palette.reagentFor(Material.STONE)).isNull();
        assertThat(palette.reagentFor(Material.STICK)).isNull();
        // Silently dropping them would leave an admin looking for a recipe the server never loaded.
        assertThat(warnings).hasSize(3);
    }

    @Test
    @DisplayName("a shade with an unreadable step falls back rather than taking the whole colour")
    void aBadStepIsClamped() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("shades.COAL.towards", "#000000");
        config.set("shades.COAL.step", 5.0);
        config.set("shades.COAL.label", "darker");

        List<String> warnings = new ArrayList<>();
        Reagent.Shade shade = (Reagent.Shade) Palette.from(config, warnings::add)
                .reagentFor(Material.COAL);

        // A step of five would land past black on the first craft and there would be no way back.
        assertThat(shade.step()).isEqualTo(0.2f);
        assertThat(warnings).hasSize(1);
    }

    @Test
    @DisplayName("a file that says nothing about the palette gives an empty one, not the defaults")
    void anEmptyFileMeansWhatItSays() {
        // An owner who deleted every dye meant it. PaletteFile is what tells a *fresh* file apart from
        // an emptied one, and it can only do that if this does not quietly refill it.
        assertThat(Palette.from(new YamlConfiguration(), warning -> {
        }).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("a colour reads back by name even after a round trip through hex")
    void coloursDescribeThemselvesByName() {
        // A style is stored on an item as hex and comes back with no idea which dye made it, so the
        // lore would say "#f38baa" unless the palette is asked to name it again.
        Palette palette = Palette.defaults();
        NameStyle stored = NameStyle.decode(
                new NameStyle(List.of(TextColor.fromHexString("#f38baa")), Set.of()).encodeColours(), "");

        assertThat(palette.nameOf(stored.colours().getFirst())).isEqualTo("pink");
        // A shaded colour is no dye's colour, and saying so beats naming the wrong dye.
        assertThat(palette.nameOf(TextColor.fromHexString("#123456"))).isEqualTo("#123456");
    }

    @Test
    @DisplayName("an item reads the same way wherever it is named")
    void itemsArePrettyPrintedOnce() {
        assertThat(Palette.pretty(Material.LIGHT_BLUE_DYE)).isEqualTo("Light blue dye");
        assertThat(Palette.pretty(Material.COAL)).isEqualTo("Coal");
    }
}
