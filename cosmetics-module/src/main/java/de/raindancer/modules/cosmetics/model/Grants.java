package de.raindancer.modules.cosmetics.model;

import net.kyori.adventure.text.format.TextDecoration;

import java.util.Set;

/**
 * What one player may wear, read off their permissions once.
 *
 * <p>Plain data so the rule that judges a style needs no server: the service reads the nodes, the rule
 * decides. Built fresh per question — permissions change while somebody is online.
 *
 * @param colour      a single colour from the palette
 * @param gradient    more than one stop
 * @param anyColour   colours that are not in the palette (typed hex codes)
 * @param animated    a gradient that flows along the name
 * @param decorations the decorations they may switch on
 * @param presets     ids of the presets they hold that need holding: restricted ones, and sold ones they bought
 * @param sold        ids of the presets this server sells, which nobody wears without holding them
 */
public record Grants(boolean colour, boolean gradient, boolean anyColour, boolean animated,
                     Set<TextDecoration> decorations, Set<String> presets, Set<String> sold) {

    /** Nobody may do anything — what a console asking on nobody's behalf gets. */
    public static final Grants NOTHING = new Grants(false, false, false, false, Set.of(), Set.of(), Set.of());

    /** With no preset for sale. */
    public Grants(boolean colour, boolean gradient, boolean anyColour, boolean animated,
                  Set<TextDecoration> decorations, Set<String> presets) {
        this(colour, gradient, anyColour, animated, decorations, presets, Set.of());
    }

    /** Without a say on animation: whoever may use a gradient may let it flow. */
    public Grants(boolean colour, boolean gradient, boolean anyColour, Set<TextDecoration> decorations,
                  Set<String> presets) {
        this(colour, gradient, anyColour, gradient, decorations, presets);
    }

    public Grants {
        decorations = Set.copyOf(decorations);
        presets = Set.copyOf(presets);
        sold = Set.copyOf(sold);
    }
}
