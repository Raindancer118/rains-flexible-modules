package de.raindancer.modules.names.model;

import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.List;
import java.util.Locale;

/**
 * What crafting one item into a name tag does to the tag's style.
 *
 * <h2>The three kinds behave differently on purpose</h2>
 * A colour <em>replaces</em>: a tag has one colour, and dyeing it again changes that colour, the way
 * dyeing anything else works. A decoration <em>toggles</em>: it is a switch, and the same item flips
 * it back. A shade <em>accumulates</em>: each piece of coal is another step darker, so it is a dial
 * rather than a switch, and the way back is the item that goes the other way.
 *
 * <p>Model rather than rules: this is what a reagent <em>is</em>, and {@code rules.CraftRule} is what
 * decides whether a grid full of them means anything.
 */
public sealed interface Reagent {

    /** The tag's style after this reagent has been crafted into it. */
    NameStyle appliedTo(NameStyle current);

    /** How this reagent reads in the manual and in a tag's lore. */
    String describe();

    /**
     * A colour, and the name to call it.
     *
     * <h2>Why the name is carried rather than derived</h2>
     * These are Minecraft's own dye colours, which are hex values — there is no chat colour called
     * "pink". Without a label the lore on a dyed tag would read {@code #f38baa}, which tells a player
     * nothing they did not already know from looking at it. The label comes from the config key, so a
     * server that adds an item gets a readable name for it without configuring one.
     */
    record Colour(TextColor colour, String label) implements Reagent {

        @Override
        public NameStyle appliedTo(NameStyle current) {
            return current.withColour(colour);
        }

        @Override
        public String describe() {
            return label;
        }
    }

    record Decoration(TextDecoration decoration) implements Reagent {

        @Override
        public NameStyle appliedTo(NameStyle current) {
            return current.toggle(decoration);
        }

        @Override
        public String describe() {
            return decoration.name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Darkens or lightens whatever colours the tag already has.
     *
     * <h2>What this is for</h2>
     * Sixteen dyes give sixteen colours, and a player who wants a deep blood red rather than the red
     * of red dye has nowhere to go. Coal darkens and glowstone dust lightens, one step at a time and
     * on every stop of a gradient at once, which turns sixteen colours into as many as anyone is
     * going to sit and craft. It is also how Minecraft's own darker chat colours are reached now that
     * the dyes map to their real colours instead of to the chat palette.
     *
     * @param label   how it reads in the manual, e.g. "darker"
     * @param towards the colour each step moves a fraction of the way towards
     * @param step    how far, per craft
     */
    record Shade(String label, TextColor towards, float step) implements Reagent {

        @Override
        public NameStyle appliedTo(NameStyle current) {
            if (current.colours().isEmpty()) {
                // Nothing to shade. Returning the style unchanged is what makes the recipe offer no
                // result, so an undyed tag does not eat a piece of coal for nothing.
                return current;
            }
            List<TextColor> shaded = current.colours().stream()
                    .map(colour -> TextColor.lerp(step, colour, towards))
                    .toList();
            return new NameStyle(shaded, current.decorations());
        }

        @Override
        public String describe() {
            return label;
        }
    }
}
