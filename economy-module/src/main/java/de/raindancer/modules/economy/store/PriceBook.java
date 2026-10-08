package de.raindancer.modules.economy.store;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.choose.Catalogue;
import de.raindancer.core.ui.choose.Category;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.model.PriceTag;
import de.raindancer.modules.economy.model.PricedNames;
import de.raindancer.modules.economy.model.RecipeShape;
import de.raindancer.modules.economy.model.SellPricing;
import de.raindancer.modules.economy.rules.MarketRule;
import de.raindancer.modules.economy.rules.PriceSolverRule;
import de.raindancer.modules.economy.rules.TradePriceRule;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;

/**
 * What everything in the shop costs and pays, worked out once per settings change and read live per click.
 *
 * <p>Four sources, most specific first: the owner's custom buy and sell prices (exact, no supply and
 * demand), the owner's custom values, the shipped raw-material list, and whatever the recipes make of
 * those. Pressure from supply and demand is read per call, so a price is never stale.
 */
public final class PriceBook {

    private record Snapshot(EconomySettings settings, Map<String, Money> values, Set<String> custom,
                            PricedNames buyPrices, PricedNames sellPrices, Set<String> notSold,
                            Set<String> notBought) {
    }

    private final java.util.function.Function<Currency, Map<String, Money>> shippedIn;
    private volatile Map<String, Money> shipped;
    private final ToDoubleFunction<String> pressure;
    private final ToIntFunction<String> stackSize;
    private final PriceSolverRule solver = new PriceSolverRule();
    private final TradePriceRule trade = new TradePriceRule();
    private final MarketRule market = new MarketRule();
    private volatile Snapshot snapshot;

    /**
     * @param pressure  supply-and-demand pressure on an item right now
     * @param stackSize how many of an item make a stack
     */
    public PriceBook(java.util.function.Function<Currency, Map<String, Money>> shipped, ToDoubleFunction<String> pressure,
                     ToIntFunction<String> stackSize) {
        this.shippedIn = shipped;
        this.shipped = Map.copyOf(shipped.apply(EconomySettings.DEFAULTS.currency()));
        this.pressure = pressure;
        this.stackSize = stackSize;
        this.snapshot = new Snapshot(EconomySettings.DEFAULTS, this.shipped, Set.of(), PricedNames.NONE,
                PricedNames.NONE, Set.of(), Set.of());
    }

    /** Works every price out again — on start, on a settings change, on {@code /eco reprice}. */
    public void recompute(EconomySettings settings, List<RecipeShape> recipes) {
        Currency currency = settings.currency();
        // Read again in the current currency: the same "2" is 200 minor units with two decimals and 2 with none.
        shipped = Map.copyOf(shippedIn.apply(currency));
        PricedNames customValues = PricedNames.parse(settings.customValues(), currency);
        Map<String, Money> base = new HashMap<>(shipped);
        customValues.amounts().forEach((name, value) -> {
            if (value.isPositive()) {
                base.put(name, value);
            }
        });
        Map<String, Money> values = settings.deriveFromRecipes()
                ? solver.solve(base, recipes, settings.craftMarkupClamped(), settings.smeltMarkupClamped())
                : base;
        snapshot = new Snapshot(settings, Map.copyOf(values), Set.copyOf(customValues.amounts().keySet()),
                PricedNames.parse(settings.buyPrices(), currency), PricedNames.parse(settings.sellPrices(), currency),
                names(settings.notSold()), names(settings.notBought()));
    }

    private static Set<String> names(List<String> list) {
        Set<String> read = new HashSet<>();
        for (String name : list == null ? List.<String>of() : list) {
            if (name != null && !name.isBlank()) {
                read.add(name.strip().toUpperCase(Locale.ROOT).replace("MINECRAFT:", ""));
            }
        }
        return read;
    }

    /** The live price tag of one item, by material name. */
    public PriceTag tag(String material) {
        String name = material.toUpperCase(Locale.ROOT);
        Snapshot now = snapshot;
        EconomySettings settings = now.settings();
        Money value = now.values().get(name);
        Optional<Money> customBuy = now.buyPrices().of(name);
        Optional<Money> customSell = now.sellPrices().of(name);
        if (value == null && customBuy.isEmpty() && customSell.isEmpty()) {
            return PriceTag.unpriced(name);
        }

        double multiplier = settings.dynamicPrices()
                ? market.multiplier(pressure.applyAsDouble(name), settings.priceSwingClamped())
                : 1.0;
        Money buy = customBuy.orElseGet(() -> value == null ? null
                : trade.unitBuy(value, multiplier, settings.buyMarkupClamped()));
        Money sell;
        if (settings.sellPricing() == SellPricing.CUSTOM_ONLY) {
            sell = customSell.orElse(null);
        } else {
            sell = customSell.orElseGet(() -> value == null ? null
                    : trade.unitSell(value, multiplier, settings.sellRatioClamped()));
        }
        if (sell != null && buy != null) {
            sell = trade.capSellBelowBuy(sell, buy);
        }

        boolean open = settings.categoryOpen(Catalogue.categoryOf(name));
        boolean buyable = settings.shopEnabled() && open && buy != null && buy.isPositive()
                && !now.notSold().contains(name);
        boolean sellable = settings.sellingEnabled() && open && sell != null && sell.isPositive()
                && !now.notBought().contains(name);
        PriceTag.Source source = customBuy.isPresent() || customSell.isPresent() || now.custom().contains(name)
                ? PriceTag.Source.CUSTOM
                : shipped.containsKey(name) ? PriceTag.Source.BASE : PriceTag.Source.RECIPE;
        return new PriceTag(name, value == null ? Money.ZERO : value, buy == null ? Money.ZERO : buy,
                sell == null ? Money.ZERO : sell, buyable, sellable, source);
    }

    /** Every item in a category the shop will buy or sell, alphabetically. */
    public List<String> tradableIn(Category category) {
        Snapshot now = snapshot;
        Set<String> candidates = new TreeSet<>(now.values().keySet());
        candidates.addAll(now.buyPrices().amounts().keySet());
        candidates.addAll(now.sellPrices().amounts().keySet());
        return candidates.stream()
                .filter(name -> Catalogue.categoryOf(name) == category)
                .filter(name -> tag(name).tradable())
                .toList();
    }

    /** Every priced item — for the admin screen, open or not. */
    public Set<String> priced() {
        Snapshot now = snapshot;
        Set<String> all = new TreeSet<>(now.values().keySet());
        all.addAll(now.buyPrices().amounts().keySet());
        all.addAll(now.sellPrices().amounts().keySet());
        return all;
    }

    public int stackSizeOf(String material) {
        return Math.max(1, stackSize.applyAsInt(material));
    }
}
