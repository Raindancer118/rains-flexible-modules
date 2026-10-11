package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;
import de.raindancer.core.social.economy.PersonalPrice;
import de.raindancer.core.social.economy.PriceModifiers;
import de.raindancer.core.social.economy.TradeSide;
import de.raindancer.modules.economy.EconomySettingsTest;
import de.raindancer.modules.economy.model.Bulk;
import de.raindancer.modules.economy.model.EnchantKind;
import de.raindancer.modules.economy.model.PriceTag;
import de.raindancer.modules.economy.model.YourPrice;
import de.raindancer.modules.economy.store.BasePrices;
import de.raindancer.modules.economy.store.PriceBook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class EnchantTradeRuleTest {

    private final EnchantTradeRule rule = new EnchantTradeRule();
    private final EnchantValueRule value = new EnchantValueRule();

    /** Every vanilla enchantment's shape that matters to pricing: maximum level, treasure, curse. */
    private static final List<EnchantKind> ALL = List.of(
            new EnchantKind("protection", 4, false, false), new EnchantKind("fire_protection", 4, false, false),
            new EnchantKind("feather_falling", 4, false, false), new EnchantKind("blast_protection", 4, false, false),
            new EnchantKind("projectile_protection", 4, false, false), new EnchantKind("respiration", 3, false, false),
            new EnchantKind("aqua_affinity", 1, false, false), new EnchantKind("thorns", 3, false, false),
            new EnchantKind("depth_strider", 3, false, false), new EnchantKind("frost_walker", 2, true, false),
            new EnchantKind("binding_curse", 1, true, true), new EnchantKind("soul_speed", 3, true, false),
            new EnchantKind("swift_sneak", 3, true, false), new EnchantKind("sharpness", 5, false, false),
            new EnchantKind("smite", 5, false, false), new EnchantKind("bane_of_arthropods", 5, false, false),
            new EnchantKind("knockback", 2, false, false), new EnchantKind("fire_aspect", 2, false, false),
            new EnchantKind("looting", 3, false, false), new EnchantKind("sweeping_edge", 3, false, false),
            new EnchantKind("efficiency", 5, false, false), new EnchantKind("silk_touch", 1, false, false),
            new EnchantKind("unbreaking", 3, false, false), new EnchantKind("fortune", 3, false, false),
            new EnchantKind("power", 5, false, false), new EnchantKind("punch", 2, false, false),
            new EnchantKind("flame", 1, false, false), new EnchantKind("infinity", 1, false, false),
            new EnchantKind("luck_of_the_sea", 3, false, false), new EnchantKind("lure", 3, false, false),
            new EnchantKind("loyalty", 3, false, false), new EnchantKind("impaling", 5, false, false),
            new EnchantKind("riptide", 3, false, false), new EnchantKind("channeling", 1, false, false),
            new EnchantKind("multishot", 1, false, false), new EnchantKind("quick_charge", 3, false, false),
            new EnchantKind("piercing", 4, false, false), new EnchantKind("density", 5, false, false),
            new EnchantKind("breach", 4, false, false), new EnchantKind("wind_burst", 3, true, false),
            new EnchantKind("lunge", 3, false, false), new EnchantKind("mending", 1, true, false),
            new EnchantKind("vanishing_curse", 1, true, true));

    private static YourPrice yours(PriceTag tag, int buyPercent, int sellPercent) {
        PersonalPrice buy = new PersonalPrice(tag.buy(), PriceModifiers.scale(tag.buy(), buyPercent, TradeSide.BUY),
                buyPercent, List.of("Role"));
        PersonalPrice sell = new PersonalPrice(tag.sell(), PriceModifiers.scale(tag.sell(), sellPercent, TradeSide.SELL),
                sellPercent, List.of("Role"));
        return new YourPrice(tag, buyPercent == 0 ? PersonalPrice.unchanged(tag.buy()) : buy,
                sellPercent == 0 ? PersonalPrice.unchanged(tag.sell()) : sell, Bulk.NONE);
    }

    @Test
    @DisplayName("every enchantment and level, any supply and demand, any role change: buying above selling, "
            + "never a profit from combining, stripping, or the plain book inside")
    void noMoneyFromAnyBook() {
        Random random = new Random(42);
        byte[] raw = "book: 70\nlapis_lazuli: 40\n".getBytes(StandardCharsets.UTF_8);
        EnchantCostRule cost = new EnchantCostRule();
        int checked = 0;
        for (int round = 0; round < 60; round++) {
            Map<String, Double> pressure = new HashMap<>();
            PriceBook book = new PriceBook(currency -> BasePrices.parse(new ByteArrayInputStream(raw), currency),
                    name -> pressure.getOrDefault(name, 0.0), name -> 64);
            String sellRatio = String.valueOf(0.1 + random.nextDouble() * 0.9);
            book.recompute(EconomySettingsTest.with("xp.buy-per-point", String.valueOf(1 + random.nextInt(20)),
                    "shop.sell-ratio", sellRatio, "shop.lowest-multiplier", "0.05", "shop.highest-multiplier", "10",
                    "shop.enchant-worth", "mending " + random.nextInt(40) + ", bane_of_arthropods 0"),
                    List.of(), List.of(), ALL);
            pressure.put("BOOK", random.nextDouble() * 10 - 5);
            for (EnchantKind kind : ALL) {
                for (int level = 1; level <= kind.maxLevel(); level++) {
                    pressure.put(kind.at(level).name(), random.nextDouble() * 10 - 5);
                }
            }
            int buyPercent = -random.nextInt(91);
            int sellPercent = random.nextInt(200) - 50;
            PriceTag plain = book.tag("BOOK");
            Money plainSell = plain.sellable() ? yours(plain, buyPercent, sellPercent).sellFor(1).orElse(Money.ZERO)
                    .max(plain.sell()) : Money.ZERO;
            for (EnchantKind kind : ALL) {
                List<Optional<Money>> lowerBuys = new ArrayList<>();
                long[] anvil = new long[kind.maxLevel()];
                for (int level = 1; level <= kind.maxLevel(); level++) {
                    PriceTag tag = book.tag(kind.at(level).name());
                    assertThat(tag.sell().minor()).isNotNegative();
                    YourPrice mine = yours(tag, buyPercent, sellPercent);
                    Optional<Money> buy = rule.buy(mine, plainSell);
                    Optional<Money> sell = rule.sell(mine, lowerBuys, anvil);
                    if (kind.curse()) {
                        assertThat(tag.tradable()).as("curses never").isFalse();
                    }
                    if (buy.isPresent()) {
                        assertThat(buy.get()).as("more than the plain book inside fetches").isGreaterThan(plainSell);
                    }
                    if (sell.isPresent() && buy.isPresent()) {
                        assertThat(sell.get()).as(kind.at(level) + " buy above sell").isLessThan(buy.get());
                        Money stripped = value.part(sell.get(), plainSell);
                        assertThat(stripped.plus(plainSell)).as("bought, stripped, bare book sold").isLessThan(buy.get());
                        checked++;
                    }
                    if (sell.isPresent() && level > 1 && lowerBuys.get(level - 2).isPresent()) {
                        assertThat(sell.get().minor()).as(kind.at(level) + " under two lower books combined")
                                .isLessThan(2 * lowerBuys.get(level - 2).get().minor() + anvil[level - 1]);
                    }
                    lowerBuys.add(buy);
                    if (level < kind.maxLevel()) {
                        anvil[level] = book.anvil(level + 1);
                    }
                }
            }
        }
        assertThat(checked).as("the property was actually exercised").isGreaterThan(1000);
    }

    @Test
    @DisplayName("a book is never cheaper to a player than the plain book in it sells for to them")
    void floorAtThePlainBook() {
        PriceTag cheap = new PriceTag("ENCHANTED_BOOK:BANE_OF_ARTHROPODS:1", Money.of(80), Money.of(80), Money.of(30),
                true, true, PriceTag.Source.RECIPE);
        assertThat(rule.buy(yours(cheap, -50, 0), Money.of(55))).contains(Money.of(56));
        assertThat(rule.buy(yours(cheap, 0, 0), Money.ZERO)).contains(Money.of(80));
    }

    @Test
    @DisplayName("a role's discount on books cannot be combined into profit: the sell price follows the player's own buys")
    void discountCannotBeCombined() {
        PriceTag second = new PriceTag("ENCHANTED_BOOK:SHARPNESS:2", Money.of(4000), Money.of(4000), Money.of(3800),
                true, true, PriceTag.Source.RECIPE);
        Optional<Money> sell = rule.sell(yours(second, 0, 0), List.of(Optional.of(Money.of(1500))), new long[] {0, 48});
        assertThat(sell).contains(Money.of(2 * 1500 + 48 - 1));
        assertThat(rule.sell(yours(second, 0, 0), List.of(Optional.empty()), new long[] {0, 48})).contains(Money.of(3800));
    }
}
