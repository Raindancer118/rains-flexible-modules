package de.raindancer.modules.economy.store;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.EconomySettingsTest;
import de.raindancer.modules.economy.model.PriceTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Potions and ominous bottles in the price book: brewed from their ingredients, otherwise exactly like items. */
class PriceBookPotionsTest {

    private static final List<String> TYPES = List.of("water", "awkward", "healing", "strong_healing", "luck",
            "regeneration", "mypack:foo");

    private PriceBook book(String... settings) {
        byte[] raw = """
                glass_bottle: 30
                nether_wart: 20
                glistering_melon_slice: 60
                glowstone_dust: 30
                gunpowder: 30
                dragon_breath: 300
                emerald: 500
                """.getBytes(StandardCharsets.UTF_8);
        PriceBook book = new PriceBook(currency -> BasePrices.parse(new ByteArrayInputStream(raw), currency),
                name -> 0.0, name -> 64);
        // Crafting adds nothing, so every sum below is the plain arithmetic.
        String[] all = new String[settings.length + 2];
        all[0] = "shop.craft-markup";
        all[1] = "0";
        System.arraycopy(settings, 0, all, 2, settings.length);
        book.recompute(EconomySettingsTest.with(all), List.of(), List.of(), List.of(), TYPES);
        return book;
    }

    @Test
    @DisplayName("a potion is a glass bottle and a third of each ingredient; splash and lingering add gunpowder and dragon's breath")
    void brewed() {
        PriceBook book = book();
        assertThat(book.tag("POTION:WATER").value()).isEqualTo(Money.of(30));
        assertThat(book.tag("POTION:AWKWARD").value()).isEqualTo(Money.of(37));
        PriceTag healing = book.tag("POTION:HEALING");
        assertThat(healing.value()).isEqualTo(Money.of(57));
        assertThat(healing.source()).isEqualTo(PriceTag.Source.RECIPE);
        assertThat(healing.buy()).isEqualTo(Money.of(57));
        assertThat(healing.sell()).isEqualTo(Money.of((long) Math.floor(57 * 0.4)));
        assertThat(healing.buyable()).isTrue();
        assertThat(healing.sellable()).isTrue();
        assertThat(book.tag("POTION:STRONG_HEALING").value()).isEqualTo(Money.of(67));
        assertThat(book.tag("SPLASH_POTION:HEALING").value()).isEqualTo(Money.of(67));
        assertThat(book.tag("LINGERING_POTION:HEALING").value()).isEqualTo(Money.of(167));
        assertThat(book.tag("potion:minecraft:healing").value()).as("as an owner writes it").isEqualTo(Money.of(57));
    }

    @Test
    @DisplayName("what cannot be worked out is not for sale: an ingredient with no price, a type nobody can brew")
    void unpriced() {
        PriceBook book = book();
        assertThat(book.tag("POTION:REGENERATION").tradable()).as("no ghast tear").isFalse();
        assertThat(book.tag("POTION:MYPACK:FOO").tradable()).isFalse();
        assertThat(book.tag("POTION:LUCK").tradable()).as("an emerald stands in").isTrue();
        assertThat(book.tag("POTION:NOTHING").tradable()).isFalse();
    }

    @Test
    @DisplayName("an owner's own value for one potion stands, and the splash and lingering forms follow it")
    void ownValue() {
        PriceBook book = book("shop.values", "potion:healing 500");
        assertThat(book.tag("POTION:HEALING").value()).isEqualTo(Money.of(500));
        assertThat(book.tag("POTION:HEALING").source()).isEqualTo(PriceTag.Source.CUSTOM);
        assertThat(book.tag("SPLASH_POTION:HEALING").value()).isEqualTo(Money.of(510));
        assertThat(book.tag("LINGERING_POTION:HEALING").value()).isEqualTo(Money.of(610));
        assertThat(book.tag("POTION:AWKWARD").value()).isEqualTo(Money.of(37));
    }

    @Test
    @DisplayName("an ominous bottle is worth the setting once per level of Bad Omen, and a level can be priced alone")
    void ominous() {
        PriceBook book = book();
        assertThat(book.tag("OMINOUS_BOTTLE:1").value()).isEqualTo(Money.of(800));
        assertThat(book.tag("OMINOUS_BOTTLE:3").value()).isEqualTo(Money.of(2400));
        assertThat(book.tag("OMINOUS_BOTTLE:5").value()).isEqualTo(Money.of(4000));
        assertThat(book.tag("OMINOUS_BOTTLE:6").tradable()).isFalse();
        assertThat(book("shop.ominous-bottle-value", "100").tag("OMINOUS_BOTTLE:2").value()).isEqualTo(Money.of(200));
        assertThat(book("shop.values", "ominous_bottle:3 9000").tag("OMINOUS_BOTTLE:3").value()).isEqualTo(Money.of(9000));
        assertThat(book().tag("OMINOUS_BOTTLE:3").buyable()).isTrue();
    }

    @Test
    @DisplayName("recipes switched off leave no brewed potion, only prices written by hand")
    void noRecipes() {
        PriceBook book = book("shop.derive-from-recipes", "false", "shop.values", "potion:healing 500");
        assertThat(book.tag("POTION:AWKWARD").tradable()).isFalse();
        assertThat(book.tag("POTION:HEALING").value()).isEqualTo(Money.of(500));
        assertThat(book.tag("SPLASH_POTION:HEALING").tradable()).as("nothing to brew it from").isFalse();
    }

    @Test
    @DisplayName("the switch closes buying, selling stays; the closed list closes a type in every form, or one form")
    void closed() {
        PriceBook off = book("shop.potions", "false");
        assertThat(off.tag("POTION:HEALING").buyable()).isFalse();
        assertThat(off.tag("POTION:HEALING").sellable()).isTrue();

        PriceBook closed = book("shop.potions-closed", "luck, splash_potion:healing, ominous_bottle:5");
        assertThat(closed.tag("POTION:LUCK").buyable()).isFalse();
        assertThat(closed.tag("SPLASH_POTION:LUCK").buyable()).isFalse();
        assertThat(closed.tag("SPLASH_POTION:HEALING").buyable()).isFalse();
        assertThat(closed.tag("POTION:HEALING").buyable()).isTrue();
        assertThat(closed.tag("OMINOUS_BOTTLE:5").buyable()).isFalse();
        assertThat(closed.tag("OMINOUS_BOTTLE:4").buyable()).isTrue();
        assertThat(closed.tag("POTION:LUCK").sellable()).as("the shop still takes them").isTrue();

        PriceBook noOminous = book("shop.potions-closed", "ominous_bottle");
        assertThat(noOminous.tag("OMINOUS_BOTTLE:2").buyable()).isFalse();
        assertThat(noOminous.tag("POTION:HEALING").buyable()).isTrue();
    }

    @Test
    @DisplayName("the shop's own switches apply: selling off, not-sold and not-bought lists")
    void switches() {
        assertThat(book("features.shop", "false").tag("POTION:HEALING").buyable()).isFalse();
        assertThat(book("features.selling", "false").tag("POTION:HEALING").sellable()).isFalse();
        assertThat(book("shop.not-sold", "potion:healing").tag("POTION:HEALING").buyable()).isFalse();
        assertThat(book("shop.not-bought", "potion:healing").tag("POTION:HEALING").sellable()).isFalse();
    }

    @Test
    @DisplayName("the bottle's item closes every potion of that form, in either list or by its drawer")
    void itemLevelClosing() {
        assertThat(book("shop.not-sold", "splash_potion").tag("SPLASH_POTION:HEALING").buyable()).isFalse();
        assertThat(book("shop.not-sold", "splash_potion").tag("POTION:HEALING").buyable()).isTrue();
        assertThat(book("shop.not-bought", "potion").tag("POTION:HEALING").sellable()).isFalse();
        assertThat(book("shop.not-bought", "potion").tag("SPLASH_POTION:HEALING").sellable()).isTrue();
        assertThat(book("shop.not-sold", "ominous_bottle").tag("OMINOUS_BOTTLE:2").buyable()).isFalse();
        String drawer = de.raindancer.modules.economy.EconomySettings.categoryKey(
                de.raindancer.core.ui.choose.Catalogue.categoryOf("POTION"));
        PriceTag closed = book(drawer, "false").tag("POTION:HEALING");
        assertThat(closed.buyable()).isFalse();
        assertThat(closed.sellable()).isFalse();
    }

    @Test
    @DisplayName("a potion bottle does not stack, an ominous bottle does")
    void stacks() {
        PriceBook book = book();
        assertThat(book.stackSizeOf("POTION:HEALING")).isEqualTo(1);
        assertThat(book.stackSizeOf("SPLASH_POTION:HEALING")).isEqualTo(1);
        assertThat(book.stackSizeOf("OMINOUS_BOTTLE:2")).isEqualTo(64);
    }

    @Test
    @DisplayName("the drawer lists what is traded, a type in each of its three forms together, ominous bottles last; the item drawers never do")
    void drawer() {
        PriceBook book = book();
        List<String> traded = book.tradablePotions();
        assertThat(traded).startsWith("POTION:WATER", "SPLASH_POTION:WATER", "LINGERING_POTION:WATER",
                "POTION:AWKWARD", "SPLASH_POTION:AWKWARD");
        assertThat(traded).contains("POTION:STRONG_HEALING", "LINGERING_POTION:LUCK")
                .doesNotContain("POTION:REGENERATION", "POTION:MYPACK:FOO");
        assertThat(traded.subList(traded.size() - 5, traded.size()))
                .containsExactly("OMINOUS_BOTTLE:1", "OMINOUS_BOTTLE:2", "OMINOUS_BOTTLE:3", "OMINOUS_BOTTLE:4",
                        "OMINOUS_BOTTLE:5");
        for (de.raindancer.core.ui.choose.Category category : de.raindancer.core.ui.choose.Category.values()) {
            assertThat(book.tradableIn(category)).noneMatch(name -> name.contains(":"));
        }
    }

    @Test
    @DisplayName("selling never pays what buying costs, and a price set by hand is the same price")
    void noLoop() {
        PriceBook book = book("shop.buy-markup", "0.0");
        for (String key : book.tradablePotions()) {
            PriceTag tag = book.tag(key);
            if (tag.sellable() && tag.buyable()) {
                assertThat(tag.sell()).isLessThan(tag.buy());
            }
        }
    }
}
