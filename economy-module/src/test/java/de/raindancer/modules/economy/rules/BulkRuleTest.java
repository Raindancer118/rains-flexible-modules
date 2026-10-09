package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;
import de.raindancer.core.social.economy.PriceModifiers;
import de.raindancer.core.ui.choose.ItemSelection;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.model.Bulk;
import de.raindancer.modules.economy.model.PriceTag;
import de.raindancer.modules.economy.model.YourPrice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BulkRuleTest {

    private final BulkRule rule = new BulkRule();
    private final ItemSelection building = ItemSelection.parse(EconomySettings.DEFAULTS.bulkItems());

    private Bulk bulk(String material) {
        return rule.forItem(material, building, BulkRule.tiers(EconomySettings.DEFAULTS.bulkTiers()),
                EconomySettings.DEFAULTS.bulkMostPercent());
    }

    @AfterEach
    void reset() {
        PriceModifiers.clear();
    }

    @Test
    @DisplayName("building blocks and iron are cheaper in bulk; diamonds, gold, ores and food are not")
    void which() {
        assertThat(bulk("STONE_BRICKS").applies()).isTrue();
        assertThat(bulk("OAK_PLANKS").applies()).isTrue();
        assertThat(bulk("GLASS").applies()).isTrue();
        assertThat(bulk("IRON_INGOT").applies()).isTrue();
        assertThat(bulk("IRON_BLOCK").applies()).isTrue();
        assertThat(bulk("DIAMOND").applies()).isFalse();
        assertThat(bulk("DIAMOND_BLOCK").applies()).isFalse();
        assertThat(bulk("GOLD_BLOCK").applies()).isFalse();
        assertThat(bulk("NETHERITE_BLOCK").applies()).isFalse();
        assertThat(bulk("IRON_ORE").applies()).isFalse();
        assertThat(bulk("COOKED_BEEF").applies()).isFalse();
    }

    @Test
    @DisplayName("5% from 2 stacks, 10% at 10 stacks, stepping up — and never past 20%")
    void tiers() {
        Bulk stone = bulk("STONE");
        assertThat(stone.percentFor(64)).isZero();
        assertThat(stone.percentFor(127)).isZero();
        assertThat(stone.percentFor(128)).isEqualTo(5);
        assertThat(stone.percentFor(639)).isEqualTo(5);
        assertThat(stone.percentFor(640)).isEqualTo(10);
        assertThat(stone.percentFor(1280)).isEqualTo(15);
        assertThat(stone.percentFor(1920)).isEqualTo(20);
        assertThat(stone.percentFor(1_000_000)).isEqualTo(20);
        assertThat(stone.most()).isEqualTo(20);
    }

    @Test
    @DisplayName("the cap is hard: an owner's 90% tier or 90% cap is held at 30%")
    void hardCap() {
        Bulk greedy = rule.forItem("STONE", building, BulkRule.tiers(List.of("64 90", "128 95")), 90);
        assertThat(greedy.percentFor(64)).isEqualTo(BulkRule.HARD_CAP);
        assertThat(greedy.most()).isEqualTo(BulkRule.HARD_CAP);
        Bulk modest = rule.forItem("STONE", building, BulkRule.tiers(List.of("64 50")), 12);
        assertThat(modest.percentFor(64)).as("the owner's own cap still holds under the hard one").isEqualTo(12);
    }

    @Test
    @DisplayName("tiers are read forgivingly: order does not matter, nonsense is skipped")
    void reading() {
        List<Bulk.Tier> read = BulkRule.tiers(List.of("640 10", "x 5", "128 5", "64", "-1 3", "256 -4"));
        assertThat(read).containsExactly(new Bulk.Tier(128, 5), new Bulk.Tier(640, 10));
    }

    @Test
    @DisplayName("ten stacks cost 10% less, and are not worth more sold back — in one go or a stack at a time")
    void noMoneyMachine() {
        // Sold for 90% of what it costs: an owner's generous custom sell price.
        PriceTag stone = new PriceTag("STONE", Money.of(100), Money.of(100), Money.of(90), true, true,
                PriceTag.Source.CUSTOM);
        YourPrice price = new PersonalPriceRule().forPlayer(UUID.randomUUID(), stone, bulk("STONE"));
        assertThat(price.buyFor(640)).contains(Money.of(57_600));
        assertThat(price.buyFor(64)).contains(Money.of(6_400));
        // The cheapest a stack can ever be bought is 80% (6,400 × 0.8 = 5,120): selling never reaches it.
        assertThat(price.sellFor(64).orElseThrow().minor()).isLessThan(5_120);
        assertThat(price.sellFor(640).orElseThrow().minor()).isLessThan(57_600);
    }

    @Test
    @DisplayName("a role's discount and bulk add up, each named")
    void withRole() {
        UUID cook = UUID.randomUUID();
        PriceModifiers.provide(org.mockito.Mockito.mock(org.bukkit.plugin.Plugin.class),
                (player, material, side) -> java.util.Optional.of(new de.raindancer.core.social.economy.PriceChange(-10, "Builder")));
        PriceTag stone = new PriceTag("STONE", Money.of(100), Money.of(100), Money.of(40), true, true,
                PriceTag.Source.BASE);
        YourPrice price = new PersonalPriceRule().forPlayer(cook, stone, bulk("STONE"));
        assertThat(price.buyFor(640)).contains(Money.of(51_200));
        assertThat(price.buyPercentFor(640)).isEqualTo(-20);
    }
}
