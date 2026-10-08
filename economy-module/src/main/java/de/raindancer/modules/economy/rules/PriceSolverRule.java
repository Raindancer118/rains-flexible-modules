package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.RecipeShape;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Prices for everything that can be made, from the prices of what it is made of.
 *
 * <p>Relaxation, as for shortest paths: every pass prices each recipe from what is known so far and keeps
 * the cheapest answer per item, until a pass changes nothing. A recipe's cost only ever rises with its
 * markup, so cycles — ingots to a block and the block back to ingots — settle rather than spiral. A raw
 * price is a fact and is never replaced by a recipe's.
 */
public final class PriceSolverRule implements IEconomyRule {

    /** Far more than any real chain is deep (log → planks → stick → torch …). */
    private static final int MOST_PASSES = 64;

    /** @return raw prices and every price that could be worked out, keyed by material name */
    public Map<String, Money> solve(Map<String, Money> base, List<RecipeShape> recipes, double craftMarkup,
                                    double smeltMarkup) {
        Map<String, Money> known = new HashMap<>(base);
        for (int pass = 0; pass < MOST_PASSES; pass++) {
            boolean changed = false;
            for (RecipeShape recipe : recipes) {
                if (base.containsKey(recipe.result())) {
                    continue;
                }
                Money cost = costOf(recipe, known, recipe.process() == RecipeShape.Process.SMELT
                        ? smeltMarkup : craftMarkup);
                if (cost == null) {
                    continue;
                }
                Money before = known.get(recipe.result());
                if (before == null || cost.isMoreThan(Money.ZERO) && before.isMoreThan(cost)) {
                    known.put(recipe.result(), cost);
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        return known;
    }

    /** One of what this recipe makes, or null when an ingredient has no price yet. */
    private static Money costOf(RecipeShape recipe, Map<String, Money> known, double markup) {
        if (recipe.slots().isEmpty()) {
            return null;
        }
        long total = 0;
        for (List<String> slot : recipe.slots()) {
            Money cheapest = null;
            for (String option : slot) {
                Money price = known.get(option);
                if (price != null && (cheapest == null || cheapest.isMoreThan(price))) {
                    cheapest = price;
                }
            }
            if (cheapest == null) {
                return null;
            }
            try {
                total = Math.addExact(total, cheapest.minor());
            } catch (ArithmeticException overflow) {
                return null;
            }
        }
        double each = total * (1.0 + Math.max(0, markup)) / recipe.amount();
        if (each >= Long.MAX_VALUE) {
            return null;
        }
        return Money.of(Math.max(1, Math.round(each)));
    }

    @Override
    public String describe() {
        return "prices for everything craftable, from the prices of its ingredients";
    }
}
