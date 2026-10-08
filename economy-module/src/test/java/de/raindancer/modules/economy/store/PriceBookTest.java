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
}
