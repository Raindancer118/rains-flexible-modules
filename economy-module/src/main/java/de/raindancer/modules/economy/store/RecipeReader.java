package de.raindancer.modules.economy.store;

import de.raindancer.modules.economy.model.RecipeShape;
import org.bukkit.Material;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmithingTransformRecipe;
import org.bukkit.inventory.StonecuttingRecipe;
import org.bukkit.inventory.TransmuteRecipe;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The server's recipes, reduced to what pricing needs. A recipe that asks for one exact item (with its own
 * name or data) is skipped: the solver prices materials, and an exact item is not one.
 */
public final class RecipeReader {

    private RecipeReader() {
    }

    public static List<RecipeShape> read(Iterator<Recipe> recipes) {
        List<RecipeShape> read = new ArrayList<>();
        while (recipes.hasNext()) {
            Recipe recipe;
            try {
                recipe = recipes.next();
            } catch (RuntimeException unreadable) {
                continue;
            }
            RecipeShape shape = shapeOf(recipe);
            if (shape != null) {
                read.add(shape);
            }
        }
        return read;
    }

    static RecipeShape shapeOf(Recipe recipe) {
        if (recipe == null || recipe.getResult().getType().isAir()) {
            return null;
        }
        String result = recipe.getResult().getType().name();
        int amount = recipe.getResult().getAmount();
        List<List<String>> slots = new ArrayList<>();
        RecipeShape.Process process = RecipeShape.Process.CRAFT;
        switch (recipe) {
            case ShapedRecipe shaped -> {
                Map<Character, RecipeChoice> choices = shaped.getChoiceMap();
                for (String row : shaped.getShape()) {
                    for (char key : row.toCharArray()) {
                        RecipeChoice choice = choices.get(key);
                        if (choice != null && !add(slots, choice)) {
                            return null;
                        }
                    }
                }
            }
            case ShapelessRecipe shapeless -> {
                for (RecipeChoice choice : shapeless.getChoiceList()) {
                    if (!add(slots, choice)) {
                        return null;
                    }
                }
            }
            case CookingRecipe<?> cooking -> {
                process = RecipeShape.Process.SMELT;
                if (!add(slots, cooking.getInputChoice())) {
                    return null;
                }
            }
            case StonecuttingRecipe cutting -> {
                process = RecipeShape.Process.CUT;
                if (!add(slots, cutting.getInputChoice())) {
                    return null;
                }
            }
            case SmithingTransformRecipe smithing -> {
                process = RecipeShape.Process.SMITH;
                if (!add(slots, smithing.getTemplate()) || !add(slots, smithing.getBase())
                        || !add(slots, smithing.getAddition())) {
                    return null;
                }
            }
            case TransmuteRecipe transmute -> {
                if (!add(slots, transmute.getInput()) || !add(slots, transmute.getMaterial())) {
                    return null;
                }
            }
            default -> {
                return null;
            }
        }
        return slots.isEmpty() ? null : new RecipeShape(result, amount, slots, process);
    }

    /** False for a choice pricing cannot read, which drops the whole recipe. */
    private static boolean add(List<List<String>> slots, RecipeChoice choice) {
        if (choice == null) {
            return true;
        }
        if (choice instanceof RecipeChoice.MaterialChoice materials) {
            List<String> names = materials.getChoices().stream().filter(material -> !material.isAir())
                    .map(Material::name).toList();
            if (names.isEmpty()) {
                return false;
            }
            slots.add(names);
            return true;
        }
        return false;
    }
}
