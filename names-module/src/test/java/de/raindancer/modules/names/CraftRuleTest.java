package de.raindancer.modules.names;

import de.raindancer.modules.names.model.Craft;
import de.raindancer.modules.names.model.Ingredient;
import de.raindancer.modules.names.model.NameStyle;
import de.raindancer.modules.names.model.Reagent;
import de.raindancer.modules.names.rules.CraftRule;
import de.raindancer.modules.names.store.Palette;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a crafting grid means.
 *
 * <p>This is the part of the module that decides whether a player's items are consumed, so it is the
 * part that gets tested hardest. Nothing here touches a server: {@link CraftRule} was deliberately given
 * {@link Ingredient} rather than {@code ItemStack} so that every rule below is checkable without booting
 * Minecraft.
 */
class CraftRuleTest {

    private static final Palette PALETTE = Palette.defaults();
    private static final CraftRule RULE = new CraftRule(PALETTE, 8);

    /**
     * The colours the shipped palette actually gives these dyes, looked up rather than written down.
     * A test that hard-codes what red dye produces stops testing anything the day that changes — which
     * it did once already, when the dyes stopped mapping onto the chat palette.
     */
    private static final TextColor RED_DYE = colourOf(Material.RED_DYE);
    private static final TextColor BLUE_DYE = colourOf(Material.BLUE_DYE);

    private static final NameStyle RED = new NameStyle(List.of(RED_DYE), Set.of());
    private static final NameStyle BLUE = new NameStyle(List.of(BLUE_DYE), Set.of());

    private static TextColor colourOf(Material dye) {
        return ((Reagent.Colour) PALETTE.reagentFor(dye)).colour();
    }

    private static Ingredient tag(int slot, NameStyle style) {
        return new Ingredient(slot, Material.NAME_TAG, false, style, 1);
    }

    private static Ingredient item(int slot, Material type) {
        return new Ingredient(slot, type, false, NameStyle.NONE, 1);
    }

    private static Craft resolve(Ingredient... grid) {
        return RULE.resolve(List.of(grid));
    }

    // ------------------------------------------------------------------ dyeing the tag

    @Test
    @DisplayName("a name tag and a dye make a tag that remembers that colour")
    void dyeingATag() {
        Craft craft = resolve(tag(0, NameStyle.NONE), item(1, Material.RED_DYE));

        assertThat(craft).isInstanceOf(Craft.StyleTag.class);
        assertThat(((Craft.StyleTag) craft).style().colours()).containsExactly(RED_DYE);
        assertThat(craft.takeOne()).containsExactlyInAnyOrder(0, 1);
        assertThat(craft.takeAll()).isEqualTo(-1);
    }

    @Test
    @DisplayName("an iron ingot makes it bold, and a second one makes it not bold again")
    void decorationsToggle() {
        Craft bolded = resolve(tag(0, RED), item(1, Material.IRON_INGOT));
        NameStyle bold = ((Craft.StyleTag) bolded).style();
        assertThat(bold.decorations()).containsExactly(TextDecoration.BOLD);
        assertThat(bold.colours()).containsExactly(RED_DYE);

        Craft unbolded = resolve(tag(0, bold), item(1, Material.IRON_INGOT));
        assertThat(((Craft.StyleTag) unbolded).style().decorations()).isEmpty();
    }

    @Test
    @DisplayName("dyeing a tag the colour it already is offers nothing, so no dye is spent")
    void aNoOpRecipeIsRefused() {
        assertThat(resolve(tag(0, RED), item(1, Material.RED_DYE))).isNull();
    }

    @Test
    @DisplayName("shading an undyed tag offers nothing, so no coal is spent")
    void shadingNothingIsRefused() {
        // The Shade reagent answers "unchanged" for a colourless tag, and the no-op guard is what turns
        // that into no result. Without it a player pays a piece of coal to darken nothing.
        assertThat(resolve(tag(0, NameStyle.NONE), item(1, Material.COAL))).isNull();
    }

    @Test
    @DisplayName("a dye that has been named is something to paint, not something to dye with")
    void aNamedReagentIsATarget() {
        Ingredient namedDye = new Ingredient(1, Material.RED_DYE, true, NameStyle.NONE, 1);

        Craft craft = RULE.resolve(List.of(tag(0, BLUE), namedDye));

        assertThat(craft).isInstanceOf(Craft.ApplyToItem.class);
        assertThat(((Craft.ApplyToItem) craft).itemSlot()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ painting an item

    @Test
    @DisplayName("a styled tag and an item paint the item's name")
    void paintingAnItem() {
        Craft craft = resolve(tag(0, RED), item(1, Material.DIAMOND_SWORD));

        assertThat(craft).isInstanceOf(Craft.ApplyToItem.class);
        Craft.ApplyToItem apply = (Craft.ApplyToItem) craft;
        assertThat(apply.style().colours()).containsExactly(RED_DYE);
        // The tag is spent; the item is not — it comes back as the result.
        assertThat(apply.takeOne()).containsExactly(0);
        assertThat(apply.takeAll()).isEqualTo(1);
    }

    @Test
    @DisplayName("red on the left and blue on the right runs red to blue, not the other way round")
    void gradientFollowsTheGrid() {
        // The layout from the original ask: tag, item, tag along the top row of a table.
        Craft craft = resolve(tag(0, RED), item(1, Material.DIAMOND_SWORD), tag(2, BLUE));

        assertThat(((Craft.ApplyToItem) craft).style().colours()).containsExactly(RED_DYE, BLUE_DYE);

        // And the mirror image really is the other gradient, not the same one.
        Craft mirrored = resolve(tag(0, BLUE), item(1, Material.DIAMOND_SWORD), tag(2, RED));
        assertThat(((Craft.ApplyToItem) mirrored).style().colours())
                .containsExactly(BLUE_DYE, RED_DYE);
    }

    @Test
    @DisplayName("the decorations of every tag in the gradient are kept")
    void gradientKeepsDecorations() {
        NameStyle redBold = new NameStyle(List.of(RED_DYE), Set.of(TextDecoration.BOLD));
        NameStyle blueItalic = new NameStyle(List.of(BLUE_DYE), Set.of(TextDecoration.ITALIC));

        Craft craft = resolve(tag(0, redBold), item(1, Material.DIAMOND_SWORD), tag(2, blueItalic));

        assertThat(((Craft.ApplyToItem) craft).style().decorations())
                .containsExactlyInAnyOrder(TextDecoration.BOLD, TextDecoration.ITALIC);
    }

    @Test
    @DisplayName("a plain tag among the styled ones is a mistake, not a stop, so nothing is made")
    void aPlainTagInTheRowRefuses() {
        // Almost certainly the tag the player meant to dye. Using it up as a colourless stop would
        // destroy it and produce a gradient they did not ask for.
        assertThat(resolve(tag(0, RED), item(1, Material.DIAMOND_SWORD), tag(2, NameStyle.NONE)))
                .isNull();
    }

    @Test
    @DisplayName("more stops than the configured maximum is refused rather than quietly truncated")
    void tooManyStops() {
        List<Ingredient> grid = List.of(
                tag(0, RED), tag(1, BLUE), tag(2, RED), item(4, Material.DIAMOND_SWORD));

        assertThat(new CraftRule(PALETTE, 2).resolve(grid)).isNull();
        assertThat(new CraftRule(PALETTE, 3).resolve(grid)).isNotNull();
    }

    @Test
    @DisplayName("a ceiling of zero out of a hand-edited file still allows a solid colour")
    void theCeilingNeverMeansNothingAtAll() {
        // Zero is not a switch anybody meant to flip, and reading it as one would turn the whole
        // feature off from a number in a file.
        assertThat(new CraftRule(PALETTE, 0).resolve(List.of(tag(0, RED), item(1, Material.DIAMOND_SWORD))))
                .isNotNull();
    }

    @Test
    @DisplayName("two items and a tag mean nothing, and are left for vanilla")
    void twoTargetsIsNotARecipe() {
        assertThat(resolve(tag(0, RED), item(1, Material.DIAMOND_SWORD), item(2, Material.STONE)))
                .isNull();
    }

    @Test
    @DisplayName("a grid with no name tag in it is never ours")
    void noTagNoCraft() {
        assertThat(resolve(item(0, Material.RED_DYE), item(1, Material.DIAMOND_SWORD))).isNull();
    }

    // ------------------------------------------------------------------ copying a style

    @Test
    @DisplayName("a styled tag beside a plain one makes two styled tags")
    void copyingAStyle() {
        Craft craft = resolve(tag(0, RED), tag(1, NameStyle.NONE));

        assertThat(craft).isInstanceOf(Craft.CopyTag.class);
        Craft.CopyTag copy = (Craft.CopyTag) craft;
        assertThat(copy.styledSlot()).isEqualTo(0);
        assertThat(copy.plainSlot()).isEqualTo(1);
        assertThat(copy.style()).isEqualTo(RED);
    }

    @Test
    @DisplayName("two dyed tags with nowhere to put the answer offer nothing")
    void twoStyledTagsWithNoBlank() {
        assertThat(resolve(tag(0, RED), tag(1, BLUE))).isNull();
    }

    // ------------------------------------------------------------------ burning a gradient onto a tag

    @Test
    @DisplayName("red tag, plain tag, blue tag makes one tag carrying the whole gradient")
    void gradientOntoATag() {
        Craft craft = resolve(tag(0, RED), tag(1, NameStyle.NONE), tag(2, BLUE));

        assertThat(craft).isInstanceOf(Craft.GradientTag.class);
        Craft.GradientTag gradient = (Craft.GradientTag) craft;
        assertThat(gradient.plainSlot()).isEqualTo(1);
        assertThat(gradient.style().colours()).containsExactly(RED_DYE, BLUE_DYE);
        // Everything on the grid is spent — the two colours and the blank they were burned onto.
        assertThat(gradient.takeOne()).containsExactlyInAnyOrder(0, 1, 2);
        assertThat(gradient.takeAll()).isEqualTo(-1);
    }

    @Test
    @DisplayName("the gradient follows the dyed tags' order, not where the blank sits")
    void blankPositionDoesNotAffectOrder() {
        // The blank is what is being written to, so where it is says nothing; the colours are the
        // statement, and their order is the whole point.
        Craft middle = resolve(tag(0, RED), tag(1, NameStyle.NONE), tag(2, BLUE));
        Craft leading = resolve(tag(0, NameStyle.NONE), tag(1, RED), tag(2, BLUE));

        assertThat(((Craft.GradientTag) middle).style())
                .isEqualTo(((Craft.GradientTag) leading).style());

        Craft mirrored = resolve(tag(0, BLUE), tag(1, NameStyle.NONE), tag(2, RED));
        assertThat(((Craft.GradientTag) mirrored).style().colours())
                .containsExactly(BLUE_DYE, RED_DYE);
    }

    @Test
    @DisplayName("a gradient tag then paints an item on its own")
    void aGradientTagIsAnOrdinaryTag() {
        // The point of burning it onto a tag: one tag, reusable, no laying the row out again.
        NameStyle gradient = NameStyle.merge(List.of(RED, BLUE));

        Craft craft = resolve(tag(0, gradient), item(1, Material.DIAMOND_SWORD));

        assertThat(((Craft.ApplyToItem) craft).style().colours()).containsExactly(RED_DYE, BLUE_DYE);
    }

    @Test
    @DisplayName("more stops than allowed is refused when burning a tag too")
    void gradientTagRespectsTheCeiling() {
        List<Ingredient> grid = List.of(tag(0, RED), tag(1, BLUE), tag(2, RED), tag(3, NameStyle.NONE));

        assertThat(new CraftRule(PALETTE, 2).resolve(grid)).isNull();
        assertThat(new CraftRule(PALETTE, 3).resolve(grid)).isNotNull();
    }

    @Test
    @DisplayName("two blanks leave no way to say which one the answer goes on")
    void twoBlanksAreAmbiguous() {
        assertThat(resolve(tag(0, RED), tag(1, BLUE), tag(2, NameStyle.NONE), tag(3, NameStyle.NONE)))
                .isNull();
    }

    @Test
    @DisplayName("two plain tags are just two name tags")
    void twoPlainTags() {
        assertThat(resolve(tag(0, NameStyle.NONE), tag(1, NameStyle.NONE))).isNull();
    }

    // ------------------------------------------------------------------ the rule itself

    @Test
    @DisplayName("a rule reading the same grid twice answers the same thing, and changes nothing")
    void theRuleIsPure() {
        // Asked once to draw the preview and again on the click that charges for it. A rule that
        // changed anything the first time would charge the player for looking.
        List<Ingredient> grid = List.of(tag(0, RED), item(1, Material.IRON_INGOT));

        assertThat(RULE.resolve(grid)).isEqualTo(RULE.resolve(grid));
        assertThat(grid.getFirst().style()).isEqualTo(RED);
    }

    @Test
    @DisplayName("the rule says what it is, with the ceiling it is holding")
    void itDescribesItself() {
        assertThat(new CraftRule(PALETTE, 3).describe()).contains("3");
    }
}
