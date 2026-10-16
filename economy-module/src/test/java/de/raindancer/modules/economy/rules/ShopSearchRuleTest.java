package de.raindancer.modules.economy.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShopSearchRuleTest {

    private final ShopSearchRule rule = new ShopSearchRule();

    @Test
    @DisplayName("every word typed has to start a word of the name, in any order and any case")
    void words() {
        assertThat(rule.score("diamond", "Diamond Sword")).isPresent();
        assertThat(rule.score("sword dia", "Diamond Sword")).isPresent();
        assertThat(rule.score("DIAMOND_SWORD", "Diamond Sword")).isPresent();
        assertThat(rule.score("iron sword", "Diamond Sword")).isEmpty();
        assertThat(rule.score("mond", "Diamond Sword")).as("the middle of a word is no match").isEmpty();
    }

    @Test
    @DisplayName("a level can be typed as a number: sharp 5 finds Sharpness V")
    void levels() {
        assertThat(rule.score("sharp 5", "Sharpness V")).isPresent();
        assertThat(rule.score("sharpness v", "Sharpness V")).isPresent();
        assertThat(rule.score("sharp 4", "Sharpness V")).isEmpty();
        assertThat(rule.score("healing 2", "Splash Potion of Healing II")).isPresent();
    }

    @Test
    @DisplayName("the closer the name, the earlier it comes: whole name, then the start, then anywhere")
    void ranking() {
        int exact = rule.score("diamond", "Diamond").orElseThrow();
        int start = rule.score("diamond", "Diamond Sword").orElseThrow();
        int later = rule.score("diamond", "Block of Diamond").orElseThrow();

        assertThat(exact).isLessThan(start);
        assertThat(start).isLessThan(later);
    }

    @Test
    @DisplayName("nothing typed finds nothing rather than everything")
    void blank() {
        assertThat(rule.score("  ", "Diamond")).isEmpty();
        assertThat(rule.score(null, "Diamond")).isEmpty();
    }

    @Test
    @DisplayName("a typo still finds it: a letter off, missing, extra or swapped; two in a long word")
    void typos() {
        assertThat(rule.score("diamnd", "Diamond")).isPresent();
        assertThat(rule.score("netherite swrod", "Netherite Sword")).isPresent();
        assertThat(rule.score("sharpnes 5", "Sharpness V")).isPresent();
        assertThat(rule.score("enchanted gloden apple", "Enchanted Golden Apple")).isPresent();
        assertThat(rule.score("efficeincy", "Efficiency V")).as("two in a long word").isPresent();
        assertThat(rule.score("dimnd", "Diamond")).as("two in a short word is something else").isEmpty();
        assertThat(rule.score("cat", "Cod")).as("short words are matched exactly").isEmpty();
    }

    @Test
    @DisplayName("a typo ranks below the real spelling")
    void typosRankLast() {
        assertThat(rule.score("diamnd", "Diamond").orElseThrow())
                .isGreaterThan(rule.score("diamond", "Block of Diamond").orElseThrow());
    }

    @Test
    @DisplayName("words that say nothing are left out: block of diamond finds the Diamond Block")
    void filler() {
        assertThat(rule.score("block of diamond", "Diamond Block")).isPresent();
        assertThat(rule.score("potion of the healing", "Potion of Healing")).isPresent();
    }
}
