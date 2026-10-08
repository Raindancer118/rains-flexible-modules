package de.raindancer.modules.economy.model;

import java.util.List;

/**
 * A recipe, reduced to what pricing needs: what it makes, how many, and what each input slot accepts.
 *
 * @param slots one entry per ingredient; each entry lists the materials that slot accepts
 */
public record RecipeShape(String result, int amount, List<List<String>> slots, Process process) {

    public enum Process { CRAFT, SMELT, CUT, SMITH }

    public RecipeShape {
        amount = Math.max(1, amount);
        slots = slots.stream().map(List::copyOf).toList();
    }
}
