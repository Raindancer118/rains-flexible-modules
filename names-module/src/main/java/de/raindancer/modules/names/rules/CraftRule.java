package de.raindancer.modules.names.rules;

import de.raindancer.modules.names.model.Craft;
import de.raindancer.modules.names.model.Ingredient;
import de.raindancer.modules.names.model.NameStyle;
import de.raindancer.modules.names.model.Reagent;
import de.raindancer.modules.names.store.Palette;

import java.util.ArrayList;
import java.util.List;

/**
 * What a crafting grid means. <strong>The single source for the recipes.</strong>
 *
 * <h2>Why this is not a set of Bukkit recipes</h2>
 * Two of the four things here cannot be expressed as one. Applying a style needs an ingredient that is
 * <em>any item at all</em>, and it needs to know where in the grid each name tag sits, because "red on
 * the left, blue on the right" is the whole point of the gradient — and a Bukkit shapeless recipe has
 * no positions while a shaped one has no wildcard. The result also depends on data the ingredients
 * carry rather than on their type, so even the recipes that could be registered would still have to
 * have their result rewritten in {@code PrepareItemCraftEvent}. Registering half of them would
 * therefore buy a recipe-book entry at the price of two code paths that can disagree, and of Bukkit
 * choosing between two of our own recipes when a grid matches both.
 *
 * <p>That is also why {@code command.NameStyleCommand} and {@code screen.PaletteMenu} exist: nothing
 * here appears in the recipe book, and a feature nobody can discover is a feature nobody has.
 *
 * <h2>Why it is rebuilt rather than held</h2>
 * Both of its fields are settings — the palette is re-read on a reload and the ceiling is a number in
 * {@code config.yml}. A rule built once at startup would keep yesterday's palette until the next
 * restart, which is the exact thing every service here takes {@code settings(...)} to avoid. It is a
 * record with two fields, so building one per craft costs nothing worth measuring.
 */
public record CraftRule(Palette palette, int maxStops) implements INamesRule {

    /**
     * Reads the grid.
     *
     * @param filled every occupied slot, in reading order
     * @return what to make, or {@code null} for a grid that means nothing to this module — which is
     *         most grids, and has to stay cheap
     */
    public Craft resolve(List<Ingredient> filled) {
        List<Ingredient> tags = filled.stream().filter(Ingredient::isNameTag).toList();
        List<Ingredient> others = filled.stream().filter(item -> !item.isNameTag()).toList();

        if (tags.isEmpty()) {
            return null;
        }

        // One tag and one reagent: dye it, or flip a decoration on it.
        if (tags.size() == 1 && others.size() == 1) {
            Ingredient other = others.getFirst();
            Reagent reagent = other.named() ? null : palette.reagentFor(other.type());
            if (reagent != null) {
                NameStyle current = tags.getFirst().style();
                NameStyle next = reagent.appliedTo(current);
                // A recipe that changes nothing must not offer a result, or the player pays a dye to
                // turn a red tag red.
                return next.equals(current)
                        ? null
                        : new Craft.StyleTag(tags.getFirst().slot(), other.slot(), next);
            }
        }

        // Tags and nothing else: write onto the blank one. Exactly one blank, because two would leave
        // no way to say which of them the answer goes on.
        if (others.isEmpty()) {
            List<Ingredient> styled = tags.stream().filter(Ingredient::isStyledTag).toList();
            List<Ingredient> plain = tags.stream().filter(tag -> !tag.isStyledTag()).toList();
            if (plain.size() != 1 || styled.isEmpty()) {
                return null;
            }
            if (styled.size() == 1) {
                // Nothing to combine, so this is a copy — and a copy can afford to give both back,
                // which is what makes stocking up for a gradient cheap.
                return new Craft.CopyTag(styled.getFirst().slot(), plain.getFirst().slot(),
                        styled.getFirst().style());
            }
            NameStyle merged = NameStyle.merge(styled.stream().map(Ingredient::style).toList());
            if (merged.colours().size() > ceiling()) {
                return null;
            }
            List<Integer> slots = new ArrayList<>();
            styled.forEach(tag -> slots.add(tag.slot()));
            return new Craft.GradientTag(plain.getFirst().slot(), slots, merged);
        }

        // Any number of styled tags and exactly one other thing: paint it.
        if (others.size() == 1) {
            Ingredient target = others.getFirst();
            // Every tag has to be styled. A plain one in the row is a mistake — most likely a player
            // reaching for the tag they meant to dye — and guessing which they meant would use it up.
            if (tags.stream().anyMatch(tag -> !tag.isStyledTag())) {
                return null;
            }
            NameStyle merged = NameStyle.merge(tags.stream().map(Ingredient::style).toList());
            if (merged.isEmpty() || merged.colours().size() > ceiling()) {
                return null;
            }
            List<Integer> slots = new ArrayList<>();
            tags.forEach(tag -> slots.add(tag.slot()));
            return new Craft.ApplyToItem(target.slot(), slots, merged);
        }

        return null;
    }

    /**
     * The most stops one name may run through, never below one.
     *
     * <p>A ceiling of zero out of a hand-edited file would otherwise mean "no gradients and no solid
     * colours either", which is the whole feature switched off by a number nobody meant as a switch.
     */
    private int ceiling() {
        return Math.max(1, maxStops);
    }

    @Override
    public String describe() {
        return "what a crafting grid of name tags means, up to " + ceiling() + " colour stops";
    }
}
