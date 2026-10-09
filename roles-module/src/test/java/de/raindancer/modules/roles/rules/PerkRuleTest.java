package de.raindancer.modules.roles.rules;

import de.raindancer.core.social.economy.PriceChange;
import de.raindancer.core.social.economy.TradeSide;
import de.raindancer.core.ui.choose.Category;
import de.raindancer.core.ui.choose.ItemSelection;
import de.raindancer.modules.roles.model.Perk;
import de.raindancer.modules.roles.model.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;


import static org.assertj.core.api.Assertions.assertThat;

class PerkRuleTest {

    private final PerkRule rule = new PerkRule();

    private static Role role(Perk... perks) {
        return new Role("builder", "Builder", "BRICKS", "#c08040", List.of(), List.of(perks));
    }

    private static Perk buy(int percent, List<Category> categories, List<String> items, List<String> except) {
        return new Perk(TradeSide.BUY, percent, new ItemSelection(categories, items, except), "");
    }

    @Test
    @DisplayName("a perk covers its categories and its items, by name or by pattern, on its own side only")
    void covers() {
        Role builder = role(buy(-20, List.of(Category.BUILDING_BLOCKS), List.of("SCAFFOLDING", "*_LOG"), List.of()));
        assertThat(rule.change(builder, "STONE_BRICKS", TradeSide.BUY)).map(PriceChange::percent).contains(-20);
        assertThat(rule.change(builder, "SCAFFOLDING", TradeSide.BUY)).map(PriceChange::percent).contains(-20);
        assertThat(rule.change(builder, "OAK_LOG", TradeSide.BUY)).isPresent();
        assertThat(rule.change(builder, "COOKED_BEEF", TradeSide.BUY)).isEmpty();
        assertThat(rule.change(builder, "STONE_BRICKS", TradeSide.SELL)).isEmpty();
        assertThat(rule.change(builder, "STONE_BRICKS", TradeSide.BUY)).map(PriceChange::reason).contains("Builder");
    }

    @Test
    @DisplayName("what a perk excepts is never covered — no cheap diamond blocks for builders")
    void except() {
        Role builder = role(buy(-20, List.of(Category.BUILDING_BLOCKS), List.of(), List.of("DIAMOND_BLOCK", "*_ORE")));
        assertThat(rule.change(builder, "DIAMOND_BLOCK", TradeSide.BUY)).isEmpty();
        assertThat(rule.change(builder, "DEEPSLATE_IRON_ORE", TradeSide.BUY)).isEmpty();
        assertThat(rule.change(builder, "STONE", TradeSide.BUY)).isPresent();
    }

    @Test
    @DisplayName("where two perks cover one item, the better one counts — they do not add up")
    void best() {
        Role explorer = role(buy(-20, List.of(Category.TRANSPORTATION), List.of(), List.of()),
                buy(-35, List.of(), List.of("FIREWORK_ROCKET"), List.of()),
                new Perk(TradeSide.SELL, 10, new ItemSelection(List.of(), List.of("FIREWORK_ROCKET"), List.of()), ""),
                new Perk(TradeSide.SELL, 15, new ItemSelection(List.of(), List.of("FIREWORK_ROCKET"), List.of()), ""));
        assertThat(rule.change(explorer, "FIREWORK_ROCKET", TradeSide.BUY)).map(PriceChange::percent).contains(-35);
        assertThat(rule.change(explorer, "FIREWORK_ROCKET", TradeSide.SELL)).map(PriceChange::percent).contains(15);
    }

    @Test
    @DisplayName("no role, no item: no change")
    void nothing() {
        assertThat(rule.change(null, "STONE", TradeSide.BUY)).isEmpty();
        assertThat(rule.change(role(), "STONE", TradeSide.BUY)).isEmpty();
        assertThat(rule.change(role(buy(-20, List.of(Category.BUILDING_BLOCKS), List.of(), List.of())), null,
                TradeSide.BUY)).isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("a perk's line for the menu says how much, which way, and on what")
    void says() {
        assertThat(buy(-25, List.of(Category.FOOD), List.of("SMOKER"), List.of()).says())
                .isEqualTo("25% off buying Food, Smoker");
        assertThat(new Perk(TradeSide.SELL, 10, new ItemSelection(List.of(), List.of("WHEAT"), List.of()), "").says())
                .isEqualTo("10% more selling Wheat");
        assertThat(new Perk(TradeSide.BUY, -5, new ItemSelection(List.of(), List.of("A"), List.of()), "rockets").says())
                .isEqualTo("5% off buying rockets");
        assertThat(buy(-20, List.of(), List.of("*_LOG", "A", "B", "C", "D", "E"), List.of()).says())
                .startsWith("20% off buying ").endsWith("and 2 more");
    }
}
