package de.raindancer.modules.economy.store;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.EconomySettingsTest;
import de.raindancer.modules.economy.model.EnchantKind;
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

    // ---------------------------------------------------------------------------- enchanted books

    private static final List<EnchantKind> ENCHANTMENTS = List.of(
            new EnchantKind("sharpness", 5, false, false), new EnchantKind("mending", 1, true, false),
            new EnchantKind("bane_of_arthropods", 5, false, false), new EnchantKind("unbreaking", 3, false, false),
            new EnchantKind("vanishing_curse", 1, true, true));

    private PriceBook enchanting(String... settings) {
        String[] all = new String[settings.length + 2];
        all[0] = "xp.buy-per-point";
        all[1] = "3";
        System.arraycopy(settings, 0, all, 2, settings.length);
        byte[] raw = """
                book: 70
                lapis_lazuli: 40
                """.getBytes(StandardCharsets.UTF_8);
        PriceBook book = new PriceBook(currency -> BasePrices.parse(new ByteArrayInputStream(raw), currency),
                material -> pressure.getOrDefault(material, 0.0), material -> 64);
        book.recompute(EconomySettingsTest.with(all), List.of(), List.of(), ENCHANTMENTS);
        return book;
    }

    @Test
    @DisplayName("each enchantment and level is an item of its own: a book plus making it — a table roll, the anvil — times its usefulness")
    void enchantedBooks() {
        PriceBook book = enchanting();
        // A table roll: 3 lapis (120) and levels 27 to 30 (306 points at 3) = 1038; sharpness is 9 over 5 levels = 1.8.
        PriceTag sharpness1 = book.tag("ENCHANTED_BOOK:SHARPNESS:1");
        assertThat(sharpness1.value()).isEqualTo(Money.of(70 + 1868));
        assertThat(sharpness1.source()).isEqualTo(PriceTag.Source.RECIPE);
        assertThat(sharpness1.buy()).isEqualTo(Money.of(70 + 1868));
        assertThat(sharpness1.sell()).isEqualTo(Money.of((long) Math.floor((70 + 1868) * 0.4)));
        assertThat(sharpness1.buyable()).isTrue();
        assertThat(sharpness1.sellable()).isTrue();
        assertThat(book.tag("enchanted_book:minecraft:sharpness:5").value()).as("as an owner writes it")
                .isEqualTo(Money.of(70 + Math.round(1.8 * 18771)));
        assertThat(book.tag("ENCHANTED_BOOK:MENDING:1").value()).as("twelve ordinary levels").isEqualTo(Money.of(70 + 12456));
        assertThat(book.tag("ENCHANTED_BOOK:SHARPNESS:6").tradable()).as("past the maximum: no such book").isFalse();
        assertThat(book.tag("ENCHANTED_BOOK:NOTHING:1").tradable()).isFalse();
        assertThat(book.stackSizeOf("ENCHANTED_BOOK:MENDING:1")).isEqualTo(1);
    }

    @Test
    @DisplayName("whatever supply and demand does: higher levels dearer, buying above selling, no level sold for more than two lower ones combined")
    void enchantedBooksNoMoneyLoop() {
        java.util.Random random = new java.util.Random(3);
        for (int round = 0; round < 200; round++) {
            pressure.clear();
            for (EnchantKind kind : ENCHANTMENTS) {
                for (int level = 1; level <= kind.maxLevel(); level++) {
                    pressure.put(kind.at(level).name(), random.nextDouble() * 10 - 5);
                }
            }
            PriceBook book = enchanting("shop.lowest-multiplier", "0.05", "shop.highest-multiplier", "10");
            for (EnchantKind kind : ENCHANTMENTS) {
                for (int level = 1; level <= kind.maxLevel(); level++) {
                    PriceTag tag = book.tag(kind.at(level).name());
                    if (tag.sellable()) {
                        assertThat(tag.sell()).isLessThan(tag.buy());
                    }
                    if (level > 1 && tag.sellable()) {
                        PriceTag lower = book.tag(kind.at(level - 1).name());
                        long anvil = new de.raindancer.modules.economy.rules.EnchantCostRule().anvil(level, 3.0);
                        assertThat(tag.sell().minor()).isLessThan(2 * lower.buy().minor() + anvil);
                    }
                }
            }
        }
        pressure.clear();
        PriceBook calm = enchanting();
        for (EnchantKind kind : ENCHANTMENTS) {
            for (int level = 2; level <= kind.maxLevel(); level++) {
                assertThat(calm.tag(kind.at(level).name()).value()).isGreaterThan(calm.tag(kind.at(level - 1).name()).value());
            }
        }
    }

    @Test
    @DisplayName("supply and demand moves one enchantment and level, nothing else")
    void enchantedBooksOwnPressure() {
        PriceBook before = enchanting();
        Money sharpness5 = before.tag("ENCHANTED_BOOK:SHARPNESS:5").buy();
        Money sharpness4 = before.tag("ENCHANTED_BOOK:SHARPNESS:4").buy();
        Money mending = before.tag("ENCHANTED_BOOK:MENDING:1").buy();
        pressure.put("ENCHANTED_BOOK:SHARPNESS:5", 1.0);
        PriceBook after = enchanting();
        assertThat(after.multiplier("ENCHANTED_BOOK:SHARPNESS:5")).isGreaterThan(1);
        assertThat(after.tag("ENCHANTED_BOOK:SHARPNESS:5").buy()).isGreaterThan(sharpness5);
        assertThat(after.tag("ENCHANTED_BOOK:SHARPNESS:4").buy()).isEqualTo(sharpness4);
        assertThat(after.tag("ENCHANTED_BOOK:MENDING:1").buy()).isEqualTo(mending);
        pressure.clear();
    }

    @Test
    @DisplayName("an owner prices a book by hand like any item: a value, a buy price, a sell price")
    void enchantedBooksCustom() {
        PriceBook book = enchanting("shop.buy-prices", "enchanted_book:mending:1 99999",
                "shop.sell-prices", "enchanted_book:minecraft:sharpness:5 100",
                "shop.values", "enchanted_book:unbreaking:1 5000");
        pressure.put("ENCHANTED_BOOK:MENDING:1", 3.0);
        assertThat(book.tag("ENCHANTED_BOOK:MENDING:1").buy()).as("exact, no supply and demand").isEqualTo(Money.of(99_999));
        assertThat(book.tag("ENCHANTED_BOOK:MENDING:1").source()).isEqualTo(PriceTag.Source.CUSTOM);
        assertThat(book.tag("ENCHANTED_BOOK:SHARPNESS:5").sell()).isEqualTo(Money.of(100));
        assertThat(book.tag("ENCHANTED_BOOK:UNBREAKING:1").value()).isEqualTo(Money.of(5000));
        assertThat(book.tag("ENCHANTED_BOOK:UNBREAKING:2").value().minor())
                .as("the level above still never under two of it combined").isGreaterThanOrEqualTo(2 * 5000 + 48);
        pressure.clear();
    }

    @Test
    @DisplayName("books follow the shop's switches: curses never, treasure when allowed, closed ones not sold, selling gated")
    void enchantedBooksGates() {
        PriceBook open = enchanting();
        assertThat(open.tag("ENCHANTED_BOOK:VANISHING_CURSE:1").tradable()).isFalse();
        assertThat(open.cursePart(new de.raindancer.modules.economy.model.EnchantKey("vanishing_curse", 1)).isPositive()).isTrue();
        assertThat(open.tag("ENCHANTED_BOOK:MENDING:1").buyable()).isTrue();

        PriceTag noTreasure = enchanting("shop.enchant-treasure", "false").tag("ENCHANTED_BOOK:MENDING:1");
        assertThat(noTreasure.buyable()).isFalse();
        assertThat(noTreasure.sellable()).as("selling them works either way").isTrue();
        assertThat(enchanting("shop.enchant-closed", "sharpness").tag("ENCHANTED_BOOK:SHARPNESS:3").buyable()).isFalse();
        assertThat(enchanting("shop.enchant-books", "false").tag("ENCHANTED_BOOK:SHARPNESS:3").buyable()).isFalse();
        assertThat(enchanting("shop.enchanted-selling", "false").tag("ENCHANTED_BOOK:SHARPNESS:3").sellable()).isFalse();
        assertThat(enchanting("shop.not-sold", "enchanted_book:sharpness:3").tag("ENCHANTED_BOOK:SHARPNESS:3").buyable()).isFalse();
        assertThat(enchanting("shop.not-bought", "enchanted_book:sharpness:3").tag("ENCHANTED_BOOK:SHARPNESS:3").sellable()).isFalse();
        assertThat(enchanting("shop.not-bought", "book").tag("ENCHANTED_BOOK:SHARPNESS:3").sellable())
                .as("what the shop does not buy, it does not buy the enchantments of").isFalse();
        assertThat(enchanting("xp.buy-per-point", "0").tag("ENCHANTED_BOOK:SHARPNESS:3").tradable())
                .as("experience without a price: nothing to work a book out from").isFalse();
    }

    @Test
    @DisplayName("the Enchantments drawer: most useful first, each from level I up; and no book in the item drawers")
    void enchantedBooksDrawer() {
        PriceBook book = enchanting();
        List<String> drawer = book.tradableEnchants();
        assertThat(drawer.getFirst()).isEqualTo("ENCHANTED_BOOK:MENDING:1");
        assertThat(drawer).containsSubsequence("ENCHANTED_BOOK:SHARPNESS:1", "ENCHANTED_BOOK:SHARPNESS:5",
                "ENCHANTED_BOOK:BANE_OF_ARTHROPODS:1");
        assertThat(drawer).doesNotContain("ENCHANTED_BOOK:VANISHING_CURSE:1");
        for (de.raindancer.core.ui.choose.Category category : de.raindancer.core.ui.choose.Category.values()) {
            assertThat(book.tradableIn(category)).noneMatch(name -> name.startsWith("ENCHANTED_BOOK:"));
        }
    }
}
