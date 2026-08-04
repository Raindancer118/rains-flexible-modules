package de.raindancer.modules.names.model;

import org.bukkit.Material;

/**
 * One occupied slot of a crafting grid, reduced to what a recipe cares about.
 *
 * <p>Plain values rather than an {@code ItemStack}, and that is the whole reason the part of this
 * module with the decisions in it can be tested without booting a server: the listener does the
 * reading and the writing, and everything between the two is pure.
 *
 * @param slot   its index in the matrix, which is reading order: left to right, top to bottom
 * @param type   the item
 * @param named  whether a player has given this item a name of its own. A named item is never a
 *               reagent — an iron ingot called "Excalibur's Ingot" is something someone is trying to
 *               paint, not something they are trying to make a name tag bold with.
 * @param style  the style it carries, {@link NameStyle#NONE} for anything that carries none
 * @param amount how many are in the stack
 */
public record Ingredient(int slot, Material type, boolean named, NameStyle style, int amount) {

    public boolean isNameTag() {
        return type == Material.NAME_TAG;
    }

    public boolean isStyledTag() {
        return isNameTag() && !style.isEmpty();
    }
}
