package de.raindancer.modules.economy.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PotionCostRuleTest {

    private final PotionCostRule rule = new PotionCostRule();

    private static final Map<String, Long> PRICES = Map.ofEntries(
            Map.entry("GLASS_BOTTLE", 30L), Map.entry("NETHER_WART", 20L), Map.entry("GLISTERING_MELON_SLICE", 60L),
            Map.entry("FERMENTED_SPIDER_EYE", 45L), Map.entry("SPIDER_EYE", 20L), Map.entry("REDSTONE", 20L),
            Map.entry("GLOWSTONE_DUST", 30L), Map.entry("GUNPOWDER", 30L), Map.entry("DRAGON_BREATH", 300L));

    private Optional<Long> value(String type, double markup) {
        return rule.value(type, PRICES::get, markup);
    }

    @Test
    @DisplayName("a potion is made from water, one ingredient per step — the chain of vanilla brewing")
    void ingredients() {
        assertThat(rule.ingredients("water")).contains(List.of());
        assertThat(rule.ingredients("awkward")).contains(List.of("NETHER_WART"));
        assertThat(rule.ingredients("healing")).contains(List.of("NETHER_WART", "GLISTERING_MELON_SLICE"));
        assertThat(rule.ingredients("strong_healing"))
                .contains(List.of("NETHER_WART", "GLISTERING_MELON_SLICE", "GLOWSTONE_DUST"));
        assertThat(rule.ingredients("harming"))
                .contains(List.of("NETHER_WART", "GLISTERING_MELON_SLICE", "FERMENTED_SPIDER_EYE"));
        assertThat(rule.ingredients("long_poison")).contains(List.of("NETHER_WART", "SPIDER_EYE", "REDSTONE"));
        assertThat(rule.ingredients("weakness")).contains(List.of("FERMENTED_SPIDER_EYE"));
        assertThat(rule.ingredients("minecraft:healing")).as("with the namespace").isEqualTo(rule.ingredients("healing"));
    }

    @Test
    @DisplayName("a type nobody knows how to brew has no recipe, rather than a guessed one")
    void unknown() {
        assertThat(rule.ingredients("mypack:foo")).isEmpty();
        assertThat(rule.ingredients("long_nonsense")).isEmpty();
        assertThat(rule.ingredients("strong_nonsense")).isEmpty();
    }

    @Test
    @DisplayName("every type vanilla has is known, so each one gets a price")
    void everyVanillaType() {
        for (String type : List.of("water", "mundane", "thick", "awkward", "night_vision", "long_night_vision",
                "invisibility", "long_invisibility", "leaping", "long_leaping", "strong_leaping", "fire_resistance",
                "long_fire_resistance", "swiftness", "long_swiftness", "strong_swiftness", "slowness", "long_slowness",
                "strong_slowness", "water_breathing", "long_water_breathing", "healing", "strong_healing", "harming",
                "strong_harming", "poison", "long_poison", "strong_poison", "regeneration", "long_regeneration",
                "strong_regeneration", "strength", "long_strength", "strong_strength", "weakness", "long_weakness",
                "luck", "turtle_master", "long_turtle_master", "strong_turtle_master", "slow_falling",
                "long_slow_falling", "wind_charged", "weaving", "oozing", "infested")) {
            assertThat(rule.ingredients(type)).as(type).isPresent();
        }
    }

    @Test
    @DisplayName("a bottle is the glass bottle plus a third of each ingredient (one fills three), and the brewing markup on that")
    void value() {
        assertThat(value("water", 0)).contains(30L);
        assertThat(value("awkward", 0)).contains(30L + 7);
        assertThat(value("healing", 0)).contains(30L + 27);
        assertThat(value("healing", 0.1)).contains(30L + 29);
        assertThat(value("strong_healing", 0)).contains(30L + 37);
    }

    @Test
    @DisplayName("an ingredient with no price leaves the potion without one")
    void unpriced() {
        assertThat(rule.value("regeneration", PRICES::get, 0)).as("no ghast tear").isEmpty();
        assertThat(rule.value("water", name -> null, 0)).as("no bottle").isEmpty();
    }

    @Test
    @DisplayName("splash adds gunpowder and lingering adds dragon's breath, a third each")
    void forms() {
        assertThat(rule.splash(57, PRICES::get, 0)).contains(57L + 10);
        assertThat(rule.lingering(67, PRICES::get, 0)).contains(67L + 100);
        assertThat(rule.splash(57, name -> null, 0)).isEmpty();
    }
}
