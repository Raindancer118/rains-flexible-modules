package de.raindancer.modules.economy.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * What a potion costs to brew, the way the game brews it: a glass bottle of water, and one ingredient per step.
 * One ingredient fills three bottles, so a bottle carries a third of each — and the brewing markup on that, as
 * crafting has.
 *
 * <p>Every step is one ingredient on a type that already exists: awkward from water and a nether wart, a
 * potion from awkward and its own ingredient, {@code long_} from the plain one and redstone, {@code strong_} with
 * glowstone, the corrupted ones from a fermented spider eye, splash with gunpowder and lingering with dragon's
 * breath. Where the game has several ways to the same thing the cheapest-to-find one is used; where it has none
 * (luck) an emerald stands in. A type this does not know how to brew has no recipe at all, and so no price of its
 * own — an owner can still give it one.
 */
public final class PotionCostRule implements IEconomyRule {

    /** What a bottle is poured into, and what stands as its water. */
    public static final String BOTTLE = "GLASS_BOTTLE";
    public static final String GUNPOWDER = "GUNPOWDER";
    public static final String DRAGON_BREATH = "DRAGON_BREATH";

    /** How many bottles one ingredient fills. */
    public static final int BOTTLES_PER_INGREDIENT = 3;

    private record Step(String parent, String ingredient) {
    }

    private static final String WATER = "water";
    private static final String AWKWARD = "awkward";

    private static final Map<String, Step> BASES = Map.ofEntries(
            Map.entry(AWKWARD, new Step(WATER, "NETHER_WART")),
            Map.entry("mundane", new Step(WATER, "SUGAR")),
            Map.entry("thick", new Step(WATER, "GLOWSTONE_DUST")),
            Map.entry("weakness", new Step(WATER, "FERMENTED_SPIDER_EYE")),
            Map.entry("night_vision", new Step(AWKWARD, "GOLDEN_CARROT")),
            Map.entry("invisibility", new Step("night_vision", "FERMENTED_SPIDER_EYE")),
            Map.entry("leaping", new Step(AWKWARD, "RABBIT_FOOT")),
            Map.entry("fire_resistance", new Step(AWKWARD, "MAGMA_CREAM")),
            Map.entry("swiftness", new Step(AWKWARD, "SUGAR")),
            Map.entry("slowness", new Step("swiftness", "FERMENTED_SPIDER_EYE")),
            Map.entry("water_breathing", new Step(AWKWARD, "PUFFERFISH")),
            Map.entry("healing", new Step(AWKWARD, "GLISTERING_MELON_SLICE")),
            Map.entry("harming", new Step("healing", "FERMENTED_SPIDER_EYE")),
            Map.entry("poison", new Step(AWKWARD, "SPIDER_EYE")),
            Map.entry("regeneration", new Step(AWKWARD, "GHAST_TEAR")),
            Map.entry("strength", new Step(AWKWARD, "BLAZE_POWDER")),
            Map.entry("luck", new Step(AWKWARD, "EMERALD")),
            Map.entry("turtle_master", new Step(AWKWARD, "TURTLE_HELMET")),
            Map.entry("slow_falling", new Step(AWKWARD, "PHANTOM_MEMBRANE")),
            Map.entry("wind_charged", new Step(AWKWARD, "BREEZE_ROD")),
            Map.entry("weaving", new Step(AWKWARD, "COBWEB")),
            Map.entry("oozing", new Step(AWKWARD, "SLIME_BLOCK")),
            Map.entry("infested", new Step(AWKWARD, "STONE")));

    /** The ingredients from a bottle of water to this type, in order; empty when this does not know the type. */
    public Optional<List<String>> ingredients(String type) {
        String id = type == null ? "" : type.toLowerCase(Locale.ROOT);
        if (id.startsWith("minecraft:")) {
            id = id.substring("minecraft:".length());
        }
        if (id.equals(WATER)) {
            return Optional.of(List.of());
        }
        String parent = null;
        String ingredient = null;
        if (id.startsWith("long_")) {
            parent = id.substring("long_".length());
            ingredient = "REDSTONE";
        } else if (id.startsWith("strong_")) {
            parent = id.substring("strong_".length());
            ingredient = "GLOWSTONE_DUST";
        } else if (BASES.containsKey(id)) {
            Step step = BASES.get(id);
            parent = step.parent();
            ingredient = step.ingredient();
        }
        if (parent == null) {
            return Optional.empty();
        }
        Optional<List<String>> before = ingredients(parent);
        if (before.isEmpty()) {
            return Optional.empty();
        }
        List<String> all = new ArrayList<>(before.get());
        all.add(ingredient);
        return Optional.of(all);
    }

    /**
     * One bottle of this type, in minor units; empty when the type is unknown or a bottle or an ingredient has
     * no price.
     *
     * @param priceOf an item's value by material name, or null when it has none
     * @param craftMarkup a fraction: 0.1 is ten percent
     */
    public Optional<Long> value(String type, Function<String, Long> priceOf, double craftMarkup) {
        Optional<List<String>> steps = ingredients(type);
        Long bottle = priceOf.apply(BOTTLE);
        if (steps.isEmpty() || bottle == null) {
            return Optional.empty();
        }
        long ingredients = 0;
        for (String ingredient : steps.get()) {
            Long price = priceOf.apply(ingredient);
            if (price == null) {
                return Optional.empty();
            }
            ingredients += price;
        }
        return Optional.of(bottle + share(ingredients, craftMarkup));
    }

    /** The same bottle as a splash potion: a third of a gunpowder more. */
    public Optional<Long> splash(long potion, Function<String, Long> priceOf, double craftMarkup) {
        Long powder = priceOf.apply(GUNPOWDER);
        return powder == null ? Optional.empty() : Optional.of(potion + share(powder, craftMarkup));
    }

    /** The same bottle as a lingering potion: a third of a dragon's breath more, on top of the splash potion. */
    public Optional<Long> lingering(long splash, Function<String, Long> priceOf, double craftMarkup) {
        Long breath = priceOf.apply(DRAGON_BREATH);
        return breath == null ? Optional.empty() : Optional.of(splash + share(breath, craftMarkup));
    }

    public static final String ARROW = "ARROW";
    /** Eight arrows round one lingering potion make eight tipped arrows. */
    public static final int ARROWS_PER_POTION = 8;

    /** One tipped arrow: an arrow and an eighth of the lingering potion, with the crafting markup on it. */
    public Optional<Long> tipped(long lingering, Function<String, Long> priceOf, double craftMarkup) {
        Long arrow = priceOf.apply(ARROW);
        if (arrow == null) {
            return Optional.empty();
        }
        return Optional.of(Math.round((arrow + lingering / (double) ARROWS_PER_POTION) * (1 + Math.max(0, craftMarkup))));
    }

    /** Whether a tipped arrow of this type does anything — water and the base potions give arrows that do not. */
    public static boolean hasArrow(String type) {
        String id = type == null ? "" : type.toLowerCase(Locale.ROOT).replace("minecraft:", "");
        return !(id.equals(WATER) || id.equals(AWKWARD) || id.equals("mundane") || id.equals("thick"));
    }

    private static long share(long ingredients, double craftMarkup) {
        return Math.round(ingredients / (double) BOTTLES_PER_INGREDIENT * (1 + Math.max(0, craftMarkup)));
    }

    @Override
    public String describe() {
        return "a potion costs a glass bottle and a third of each ingredient it is brewed from";
    }
}
