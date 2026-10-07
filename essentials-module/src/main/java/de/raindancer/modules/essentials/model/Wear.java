package de.raindancer.modules.essentials.model;

/**
 * How worn one stack of items is — what a repair rule judges, with no item in sight.
 *
 * @param holding    whether there is anything there at all
 * @param damageable whether it can wear (a tool or armour, not a block)
 * @param damage     how much it has worn; 0 is as new
 */
public record Wear(boolean holding, boolean damageable, int damage) {

    public static final Wear EMPTY = new Wear(false, false, 0);
}
