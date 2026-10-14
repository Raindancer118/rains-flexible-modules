package de.raindancer.modules.enchants.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GiveRuleTest {

    private final GiveRule rule = new GiveRule(Map.of("rainsenchants:flight", 5, "rainsenchants:telekinesis", 1,
            "rainsenchants:auto_sell", 1));

    @Test
    @DisplayName("an enchantment's shard, with or without the namespace")
    void shards() {
        assertThat(rule.parse("telekinesis_shard")).contains(new GiveRule.Request("rainsenchants:telekinesis", 0));
        assertThat(rule.parse("rainsenchants:auto_sell_shard")).contains(new GiveRule.Request("rainsenchants:auto_sell", 0));
    }

    @Test
    @DisplayName("a book at its highest level, or the level asked for — never above the highest")
    void books() {
        assertThat(rule.parse("flight_book")).contains(new GiveRule.Request("rainsenchants:flight", 5));
        assertThat(rule.parse("rainsenchants:flight_book_3")).contains(new GiveRule.Request("rainsenchants:flight", 3));
        assertThat(rule.parse("flight_book_6")).isEmpty();
        assertThat(rule.parse("flight_book_0")).isEmpty();
        assertThat(rule.parse("auto_sell_book")).contains(new GiveRule.Request("rainsenchants:auto_sell", 1));
    }

    @Test
    @DisplayName("anything else is the game's own item, left to its /give")
    void vanilla() {
        assertThat(rule.parse("diamond_sword")).isEmpty();
        assertThat(rule.parse("minecraft:echo_shard")).isEmpty();
        assertThat(rule.parse("nothing_shard")).isEmpty();
        assertThat(rule.parse("diamond_pickaxe[enchantments={\"rainsenchants:flight\":1}]")).isEmpty();
    }

    @Test
    @DisplayName("every name it knows, for tab completion")
    void names() {
        assertThat(rule.names()).contains("rainsenchants:flight_shard", "rainsenchants:flight_book",
                "rainsenchants:flight_book_5", "rainsenchants:telekinesis_book").doesNotContain("rainsenchants:flight_book_6");
    }
}
