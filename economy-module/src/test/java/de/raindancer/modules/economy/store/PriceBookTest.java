package de.raindancer.modules.economy.store;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.EconomySettingsTest;
import de.raindancer.modules.economy.model.PriceTag;
import de.raindancer.modules.economy.model.RecipeShape;
import de.raindancer.modules.economy.model.SellPricing;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PriceBookTest {

    private final Map<String, Double> pressure = new HashMap<>();

    private PriceBook book(EconomySettings settings, List<RecipeShape> recipes) {
        byte[] raw = """
                # raw materials
                oak_log: 200
                diamond: 10000
                raw_iron: 800
                """.getBytes(StandardCharsets.UTF_8);
        PriceBook book = new PriceBook(currency -> BasePrices.parse(new ByteArrayInputStream(raw), currency), material -> pressure.getOrDefault(material, 0.0), material -> 64);
        book.recompute(settings, recipes);
        return book;
    }

    private static final List<RecipeShape> PLANKS = List.of(new RecipeShape("OAK_PLANKS", 4,
            List.of(List.of("OAK_LOG")), RecipeShape.Process.CRAFT));

    @Test
    @DisplayName("the shipped list reads plain names and decimals, and skips what it cannot read")
    void basePrices() {
        Map<String, Money> read = BasePrices.parse(new ByteArrayInputStream(
                "dirt: 5\nnonsense\nstone: x\nhalf: 0.5\n  # comment\nDIAMOND: 100\n".getBytes(StandardCharsets.UTF_8)),
                EconomySettings.DEFAULTS.currency());
        assertThat(read).containsOnlyKeys("DIRT", "DIAMOND");
        assertThat(read.get("DIRT")).isEqualTo(Money.of(5));
    }

    @Test
    @DisplayName("by default: raw prices, recipe prices, buying at value and selling at the ratio")
    void automatic() {
        PriceBook book = book(EconomySettings.DEFAULTS, PLANKS);
        PriceTag diamond = book.tag("DIAMOND");
        assertThat(diamond.source()).isEqualTo(PriceTag.Source.BASE);
        assertThat(diamond.buy()).isEqualTo(Money.of(10_000));
        assertThat(diamond.sell()).isEqualTo(Money.of(4_000));
        assertThat(diamond.buyable()).isTrue();
        assertThat(diamond.sellable()).isTrue();

        PriceTag planks = book.tag("OAK_PLANKS");
        assertThat(planks.source()).isEqualTo(PriceTag.Source.RECIPE);
        assertThat(planks.value()).isEqualTo(Money.of(55));

        assertThat(book.tag("BEDROCK").tradable()).isFalse();
    }

    @Test
    @DisplayName("recipes can be switched off, leaving only what has a price of its own")
    void noRecipes() {
        EconomySettings settings = EconomySettingsTest.with("shop.derive-from-recipes", "false");
        assertThat(book(settings, PLANKS).tag("OAK_PLANKS").tradable()).isFalse();
    }

    @Test
    @DisplayName("a custom value replaces the shipped one, and what is crafted from it follows")
    void customValues() {
        EconomySettings settings = EconomySettingsTest.with("shop.values", "oak_log 400");
        PriceBook book = book(settings, PLANKS);
        assertThat(book.tag("OAK_LOG").source()).isEqualTo(PriceTag.Source.CUSTOM);
        assertThat(book.tag("OAK_PLANKS").value()).isEqualTo(Money.of(110));
    }

    @Test
    @DisplayName("a custom sell price wins over the automatic one, but never reaches the buy price")
    void customSell() {
        EconomySettings settings = EconomySettingsTest.with("shop.sell-prices", "diamond 6000, raw_iron 5000");
        PriceBook book = book(settings, PLANKS);
        assertThat(book.tag("DIAMOND").sell()).isEqualTo(Money.of(6_000));
        assertThat(book.tag("DIAMOND").source()).isEqualTo(PriceTag.Source.CUSTOM);
        assertThat(book.tag("RAW_IRON").sell()).as("kept below buying").isEqualTo(Money.of(799));
        assertThat(book.tag("OAK_LOG").sell()).as("everything else stays automatic").isEqualTo(Money.of(80));
    }

    @Test
    @DisplayName("custom-only selling buys back exactly what is listed and nothing else")
    void customOnly() {
        EconomySettings settings = EconomySettingsTest.with("shop.sell-pricing", "custom_only",
                "shop.sell-prices", "diamond 60");
        PriceBook book = book(settings, PLANKS);
        assertThat(book.tag("DIAMOND").sellable()).isTrue();
        assertThat(book.tag("OAK_LOG").sellable()).isFalse();
        assertThat(book.tag("OAK_LOG").buyable()).as("buying is unaffected").isTrue();
        assertThat(settings.sellPricing()).isEqualTo(SellPricing.CUSTOM_ONLY);
    }

    @Test
    @DisplayName("a custom buy price is exact, even for something with no other price")
    void customBuy() {
        EconomySettings settings = EconomySettingsTest.with("shop.buy-prices", "elytra 500000");
        PriceTag elytra = book(settings, PLANKS).tag("ELYTRA");
        assertThat(elytra.buyable()).isTrue();
        assertThat(elytra.buy()).isEqualTo(Money.of(500_000));
        assertThat(elytra.sellable()).as("nothing says what it sells for").isFalse();
    }

    @Test
    @DisplayName("items and whole categories can be closed one by one")
    void closing() {
        assertThat(book(EconomySettingsTest.with("shop.not-sold", "diamond"), PLANKS).tag("DIAMOND").buyable()).isFalse();
        assertThat(book(EconomySettingsTest.with("shop.not-bought", "diamond"), PLANKS).tag("DIAMOND").sellable()).isFalse();
        PriceTag closed = book(EconomySettingsTest.with("shop.category.building-blocks", "false"), PLANKS).tag("OAK_PLANKS");
        assertThat(closed.tradable()).isFalse();
        assertThat(book(EconomySettingsTest.with("features.selling", "false"), PLANKS).tag("DIAMOND").sellable()).isFalse();
        assertThat(book(EconomySettingsTest.with("features.shop", "false"), PLANKS).tag("DIAMOND").buyable()).isFalse();
    }

    @Test
    @DisplayName("demand raises both prices together, so selling never overtakes buying")
    void demand() {
        PriceBook book = book(EconomySettings.DEFAULTS, PLANKS);
        pressure.put("DIAMOND", 2.0);
        PriceTag dear = book.tag("DIAMOND");
        assertThat(dear.buy()).isGreaterThan(Money.of(10_000));
        assertThat(dear.sell()).isGreaterThan(Money.of(4_000)).isLessThan(dear.buy());

        PriceTag fixed = book(EconomySettingsTest.with("features.dynamic-prices", "false"), PLANKS).tag("DIAMOND");
        assertThat(fixed.buy()).isEqualTo(Money.of(10_000));
    }

    @Test
    @DisplayName("what a category offers is listed, sorted, and only what can be traded")
    void listing() {
        PriceBook book = book(EconomySettings.DEFAULTS, PLANKS);
        assertThat(book.tradableIn(de.raindancer.core.ui.choose.Category.BUILDING_BLOCKS)).contains("OAK_LOG", "OAK_PLANKS");
        assertThat(book.tradableIn(de.raindancer.core.ui.choose.Category.MISC)).contains("DIAMOND", "RAW_IRON");
    }

    @Test
    @DisplayName("spawn eggs: every egg at the egg value, in a drawer of their own, bosses closed, custom prices win")
    void spawnEggs() {
        List<String> eggs = List.of("PIG_SPAWN_EGG", "ZOMBIE_SPAWN_EGG", "WITHER_SPAWN_EGG", "ENDER_DRAGON_SPAWN_EGG");
        PriceBook book = new PriceBook(currency -> Map.of(), material -> 0.0, material -> 64);
        book.recompute(EconomySettingsTest.with("shop.buy-prices", "zombie_spawn_egg 9000"), List.of(), eggs);
        assertThat(book.tag("PIG_SPAWN_EGG").buy()).isEqualTo(Money.of(2_000));
        assertThat(book.tag("PIG_SPAWN_EGG").buyable()).isTrue();
        assertThat(book.tag("ZOMBIE_SPAWN_EGG").buy()).isEqualTo(Money.of(9_000));
        assertThat(book.tag("WITHER_SPAWN_EGG").buyable()).as("a boss's egg is not for sale").isFalse();
        assertThat(book.tradableEggs()).containsExactly("PIG_SPAWN_EGG", "ZOMBIE_SPAWN_EGG");
        assertThat(book.tradableIn(de.raindancer.core.ui.choose.Category.MISC))
                .as("not mixed into Everything Else").noneMatch(name -> name.endsWith("_SPAWN_EGG"));

        book.recompute(EconomySettingsTest.with("shop.spawn-eggs", "false"), List.of(), eggs);
        assertThat(book.tag("PIG_SPAWN_EGG").buyable()).isFalse();
        assertThat(book.tradableEggs()).isEmpty();
    }

    @Test
    @DisplayName("netherite: the shipped list prices the upgrade template, so smithing prices every netherite piece")
    void netherite() {
        var shipped = BasePrices.parse(EconomySettings.class.getResourceAsStream("base-prices.yml"),
                EconomySettings.DEFAULTS.currency());
        assertThat(shipped).containsKeys("NETHERITE_SCRAP", "NETHERITE_UPGRADE_SMITHING_TEMPLATE");
        List<RecipeShape> recipes = List.of(
                new RecipeShape("NETHERITE_INGOT", 1, List.of(List.of("NETHERITE_SCRAP"), List.of("NETHERITE_SCRAP"),
                        List.of("NETHERITE_SCRAP"), List.of("NETHERITE_SCRAP"), List.of("GOLD_INGOT"),
                        List.of("GOLD_INGOT"), List.of("GOLD_INGOT"), List.of("GOLD_INGOT")), RecipeShape.Process.CRAFT),
                new RecipeShape("GOLD_INGOT", 1, List.of(List.of("RAW_GOLD")), RecipeShape.Process.SMELT),
                new RecipeShape("DIAMOND_SWORD", 1, List.of(List.of("DIAMOND"), List.of("DIAMOND"), List.of("STICK")),
                        RecipeShape.Process.CRAFT),
                new RecipeShape("STICK", 4, List.of(List.of("OAK_PLANKS"), List.of("OAK_PLANKS")), RecipeShape.Process.CRAFT),
                new RecipeShape("OAK_PLANKS", 4, List.of(List.of("OAK_LOG")), RecipeShape.Process.CRAFT),
                new RecipeShape("NETHERITE_SWORD", 1, List.of(List.of("NETHERITE_UPGRADE_SMITHING_TEMPLATE"),
                        List.of("DIAMOND_SWORD"), List.of("NETHERITE_INGOT")), RecipeShape.Process.SMITH));
        PriceBook book = new PriceBook(currency -> shipped, material -> 0.0, material -> 64);
        book.recompute(EconomySettings.DEFAULTS, recipes);
        assertThat(book.tag("NETHERITE_INGOT").buyable()).isTrue();
        assertThat(book.tag("NETHERITE_SWORD").buyable()).as("netherite gear is for sale").isTrue();
        assertThat(book.tag("NETHERITE_SWORD").buy()).isGreaterThan(book.tag("NETHERITE_INGOT").buy());
    }

    @Test
    @DisplayName("hanging signs: a stripped log costs what its log costs, so the sign made of it has a price")
    void hangingSigns() {
        List<RecipeShape> recipes = new java.util.ArrayList<>(RecipeReader.inWorld(List.of("OAK_LOG", "STRIPPED_OAK_LOG")));
        recipes.add(new RecipeShape("OAK_HANGING_SIGN", 6, List.of(List.of("IRON_CHAIN"), List.of("IRON_CHAIN"),
                List.of("STRIPPED_OAK_LOG"), List.of("STRIPPED_OAK_LOG"), List.of("STRIPPED_OAK_LOG"),
                List.of("STRIPPED_OAK_LOG"), List.of("STRIPPED_OAK_LOG"), List.of("STRIPPED_OAK_LOG")),
                RecipeShape.Process.CRAFT));
        recipes.add(new RecipeShape("IRON_CHAIN", 1, List.of(List.of("RAW_IRON")), RecipeShape.Process.CRAFT));
        PriceBook book = book(EconomySettings.DEFAULTS, recipes);
        assertThat(book.tag("STRIPPED_OAK_LOG").value()).as("stripping is free").isEqualTo(book.tag("OAK_LOG").value());
        assertThat(book.tag("OAK_HANGING_SIGN").buyable()).isTrue();
    }

    @Test
    @DisplayName("supply and demand: sold off it is worth less, bought up it costs more, each up to its limit")
    void supplyAndDemand() {
        PriceBook book = book(EconomySettingsTest.with("shop.buy-prices", "raw_iron 500"), PLANKS);
        pressure.put("DIAMOND", -1000.0);
        assertThat(book.multiplier("DIAMOND")).isCloseTo(0.5, org.assertj.core.data.Offset.offset(0.01));
        assertThat(book.tag("DIAMOND").buy()).isEqualTo(Money.of(5_000));
        assertThat(book.tag("DIAMOND").sell()).isEqualTo(Money.of(2_000));
        pressure.put("DIAMOND", 1000.0);
        assertThat(book.tag("DIAMOND").buy()).isEqualTo(Money.of(20_000));
        assertThat(book.tag("DIAMOND").sell()).isEqualTo(Money.of(8_000));
        pressure.put("RAW_IRON", 1000.0);
        assertThat(book.multiplier("RAW_IRON")).as("an owner's own price does not move").isEqualTo(1.0);
        assertThat(book.tag("RAW_IRON").buy()).isEqualTo(Money.of(500));
        assertThat(book(EconomySettingsTest.with("features.dynamic-prices", "false"), PLANKS).multiplier("DIAMOND"))
                .as("switched off").isEqualTo(1.0);
    }

    @Test
    @DisplayName("a bottle o' enchanting is worth the points it holds on average, at the shop's price for a point")
    void experienceBottle() {
        PriceTag bottle = book(EconomySettings.DEFAULTS, PLANKS).tag("EXPERIENCE_BOTTLE");
        assertThat(bottle.buyable()).isTrue();
        assertThat(bottle.value()).isEqualTo(EconomySettings.DEFAULTS.xpBuyMoney().times(PriceBook.POINTS_PER_BOTTLE));
        PriceTag dearer = book(EconomySettingsTest.with("xp.buy-per-point", "10"), PLANKS).tag("EXPERIENCE_BOTTLE");
        assertThat(dearer.value()).isEqualTo(Money.of(10L * PriceBook.POINTS_PER_BOTTLE));
        PriceTag owners = book(EconomySettingsTest.with("shop.values", "experience_bottle 500"), PLANKS)
                .tag("EXPERIENCE_BOTTLE");
        assertThat(owners.value()).as("an owner's own value wins").isEqualTo(Money.of(500));
    }
}
