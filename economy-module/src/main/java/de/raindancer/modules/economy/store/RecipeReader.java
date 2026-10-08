package de.raindancer.modules.economy.store;

import de.raindancer.modules.economy.model.RecipeShape;
import org.bukkit.Material;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import net.kyori.adventure.key.Key;
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
        // Paper hands smithing ingredients over as item types, not as materials.
        if (choice instanceof RecipeChoice.ItemTypeChoice types) {
            List<String> names;
            try {
                // Resolved rather than read: netherite's ingot slot is a tag (#netherite_tool_materials), whose
                // members only the registry knows.
                names = materialNames(types.itemTypes().resolve(org.bukkit.Registry.ITEM).stream()
                        .map(org.bukkit.Keyed::getKey).toList());
            } catch (RuntimeException unreadable) {
                return false;
            }
            if (names.isEmpty()) {
                return false;
            }
            slots.add(names);
            return true;
        }
        return false;
    }

    /**
     * What tools, water and time make, as recipes the server's list does not have: an axe strips a log, a shovel
     * makes a path, a hoe farmland, water turns dirt to mud and powder to concrete, and copper ages. Each costs
     * what it is made from. Without these, nothing made of them — hanging signs, for one — had a price.
     *
     * @param materials every item name on the server; only pairs that both exist become recipes
     */
    public static List<RecipeShape> inWorld(java.util.Collection<String> materials) {
        java.util.Set<String> known = new java.util.HashSet<>(materials);
        List<RecipeShape> shapes = new ArrayList<>();
        java.util.function.BiConsumer<String, List<String>> add = (result, from) -> {
            List<String> present = from.stream().filter(known::contains).toList();
            if (known.contains(result) && !present.isEmpty()) {
                shapes.add(new RecipeShape(result, 1, List.of(present), RecipeShape.Process.WORLD));
            }
        };
        add.accept("DIRT_PATH", List.of("DIRT", "GRASS_BLOCK"));
        add.accept("FARMLAND", List.of("DIRT", "GRASS_BLOCK"));
        add.accept("MUD", List.of("DIRT"));
        for (String name : materials) {
            if (name.startsWith("STRIPPED_")) {
                add.accept(name, List.of(name.substring("STRIPPED_".length())));
            } else if (name.endsWith("_CONCRETE")) {
                add.accept(name, List.of(name + "_POWDER"));
            } else if (name.startsWith("EXPOSED_")) {
                String plain = name.substring("EXPOSED_".length());
                add.accept(name, List.of(known.contains(plain) ? plain : plain + "_BLOCK"));
            } else if (name.startsWith("WEATHERED_")) {
                add.accept(name, List.of("EXPOSED_" + name.substring("WEATHERED_".length())));
            } else if (name.startsWith("OXIDIZED_")) {
                add.accept(name, List.of("WEATHERED_" + name.substring("OXIDIZED_".length())));
            }
        }
        return shapes;
    }

    /** Vanilla item keys as material names; anything from another namespace is left out. */
    static List<String> materialNames(Iterable<? extends Key> keys) {
        List<String> names = new ArrayList<>();
        for (Key key : keys) {
            if (key.namespace().equals(Key.MINECRAFT_NAMESPACE)) {
                names.add(key.value().toUpperCase(java.util.Locale.ROOT));
            }
        }
        return names;
    }
}
