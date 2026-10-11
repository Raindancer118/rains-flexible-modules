package de.raindancer.modules.economy.store;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.choose.Catalogue;
import de.raindancer.core.ui.choose.Category;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.model.EnchantKey;
import de.raindancer.modules.economy.model.EnchantKind;
import de.raindancer.modules.economy.model.PriceTag;
import de.raindancer.modules.economy.model.PricedNames;
import de.raindancer.modules.economy.model.RecipeShape;
import de.raindancer.modules.economy.model.SellPricing;
import de.raindancer.modules.economy.rules.EnchantCostRule;
import de.raindancer.modules.economy.rules.EnchantValueRule;
import de.raindancer.modules.economy.rules.EnchantWorthRule;
import de.raindancer.modules.economy.rules.MarketRule;
import de.raindancer.modules.economy.rules.PriceSolverRule;
import de.raindancer.modules.economy.rules.TradePriceRule;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 *
 * <p>Enchanted books are priced one enchantment and level at a time ({@link EnchantKey}), from what making
 * them costs ({@link EnchantCostRule}: the book, lapis, experience at the bottle's price, the anvil) times
 * how useful the enchantment is ({@link EnchantWorthRule}) — and otherwise exactly like any item.
 */
public final class PriceBook {

    /** What a thrown bottle o' enchanting gives on average: 3 to 11 points. */
    public static final int POINTS_PER_BOTTLE = 7;

    private static final String BOTTLE = "EXPERIENCE_BOTTLE";
    private static final String BOOK = "BOOK";
    private static final String LAPIS = "LAPIS_LAZULI";
    /** Beyond this a price means nothing; sums of a few of them stay far from overflow. */
    private static final long MOST = Long.MAX_VALUE / 64;

    private record Snapshot(EconomySettings settings, Map<String, Money> values, Set<String> custom,
                            PricedNames buyPrices, PricedNames sellPrices, Set<String> notSold,
                            Set<String> notBought, List<String> eggs, Map<String, EnchantKind> enchantments,
                            EnchantWorthRule worth, double pointValue) {
    }

    private static final String EGG = "_SPAWN_EGG";

    private final java.util.function.Function<Currency, Map<String, Money>> shippedIn;
    private volatile Map<String, Money> shipped;
    private final ToDoubleFunction<String> pressure;
    private final ToIntFunction<String> stackSize;
    private final PriceSolverRule solver = new PriceSolverRule();
    private final TradePriceRule trade = new TradePriceRule();
    private final MarketRule market = new MarketRule();
    private final EnchantCostRule enchantCost = new EnchantCostRule();
    private final EnchantValueRule enchantValue = new EnchantValueRule();
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
                PricedNames.NONE, Set.of(), Set.of(), List.of(), Map.of(), new EnchantWorthRule(List.of()), 0);
    }

    /** Works every price out again — on start, on a settings change, on {@code /eco reprice}. */
    public void recompute(EconomySettings settings, List<RecipeShape> recipes) {
        recompute(settings, recipes, List.of());
    }

    /**
     * @param eggs every spawn egg this server has, by material name — priced at the egg value when the
     *             owner sells them, unless an egg already has a value of its own
     */
    public void recompute(EconomySettings settings, List<RecipeShape> recipes, List<String> eggs) {
        recompute(settings, recipes, eggs, List.copyOf(snapshot.enchantments().values()));
    }

    /** @param enchantments every enchantment this server knows — each level of each gets a price of its own */
    public void recompute(EconomySettings settings, List<RecipeShape> recipes, List<String> eggs,
                          List<EnchantKind> enchantments) {
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
        Map<String, Money> values = new HashMap<>(settings.deriveFromRecipes()
                ? solver.solve(base, recipes, settings.craftMarkupClamped(), settings.smeltMarkupClamped())
                : base);
        // No recipe makes one, so the solver never prices it; it is worth the points it holds, which the shop
        // already sells by the point. Priced after the recipes so an owner's own value (in base) still wins.
        if (!base.containsKey(BOTTLE)) {
            try {
                values.put(BOTTLE, settings.xpBuyMoney().times(POINTS_PER_BOTTLE));
            } catch (ArithmeticException tooDear) {
                values.remove(BOTTLE);
            }
        }
        Set<String> closedEggs = new HashSet<>();
        for (String mob : names(settings.spawnEggsClosed())) {
            closedEggs.add(mob.endsWith(EGG) ? mob : mob + EGG);
        }
        Set<String> openEggs = new TreeSet<>();
        for (String egg : eggs) {
            String name = egg.toUpperCase(Locale.ROOT);
            if (settings.spawnEggs() && name.endsWith(EGG) && !closedEggs.contains(name)) {
                openEggs.add(name);
                values.putIfAbsent(name, settings.spawnEggValueMoney());
            }
        }
        Map<String, EnchantKind> kinds = new LinkedHashMap<>();
        for (EnchantKind kind : enchantments == null ? List.<EnchantKind>of() : enchantments) {
            kinds.put(kind.id(), kind);
        }
        EnchantWorthRule worth = settings.enchantWorthTable();
        double pointValue = values.containsKey(BOTTLE) ? values.get(BOTTLE).minor() / (double) POINTS_PER_BOTTLE : 0;
        priceEnchantments(values, base, kinds.values(), worth, pointValue);
        snapshot = new Snapshot(settings, Map.copyOf(values), Set.copyOf(customValues.amounts().keySet()),
                PricedNames.parse(settings.buyPrices(), currency), PricedNames.parse(settings.sellPrices(), currency),
                names(settings.notSold()), names(settings.notBought()), List.copyOf(openEggs), Map.copyOf(kinds),
                worth, pointValue);
    }

    /**
     * Every level of every enchantment: a book plus the enchantment's ladder. An owner's own value for a level
     * stands, and the levels above still never cost less than combining two of it.
     */
    private void priceEnchantments(Map<String, Money> values, Map<String, Money> base, Iterable<EnchantKind> kinds,
                                   EnchantWorthRule worth, double pointValue) {
        long book = Math.min(MOST, Math.max(0, values.getOrDefault(BOOK, Money.ZERO).minor()));
        double roll = enchantCost.roll(pointValue, values.getOrDefault(LAPIS, Money.ZERO).minor());
        for (EnchantKind kind : kinds) {
            double factor = Math.abs(worth.factor(kind.id(), kind.maxLevel(), kind.treasure(), kind.curse()));
            long[] ladder = roll > 0 ? enchantCost.ladder(kind.maxLevel(), factor, roll, book, pointValue) : null;
            long previous = -1;
            for (int level = 1; level <= Math.max(1, kind.maxLevel()); level++) {
                String key = kind.at(level).name();
                Money own = base.get(key);
                long value;
                if (own != null) {
                    value = Math.min(MOST, own.minor());
                } else if (ladder != null) {
                    value = Math.min(MOST, book + ladder[level - 1]);
                    if (previous >= 0) {
                        value = Math.max(value, Math.min(MOST, 2 * previous + enchantCost.anvil(level, pointValue)));
                    }
                } else {
                    previous = -1;
                    continue;
                }
                values.put(key, Money.of(value));
                previous = value;
            }
        }
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

    /** The live price tag of one item, by material name — or of one enchanted book, by its {@link EnchantKey}. */
    public PriceTag tag(String material) {
        Optional<EnchantKey> enchant = EnchantKey.parse(material);
        if (enchant.isPresent()) {
            return enchantTag(enchant.get(), snapshot);
        }
        String name = material.toUpperCase(Locale.ROOT);
        Snapshot now = snapshot;
        EconomySettings settings = now.settings();
        Raw raw = raw(name, now);
        if (raw == null) {
            return PriceTag.unpriced(name);
        }
        boolean open = name.endsWith(EGG) ? now.eggs().contains(name)
                : settings.categoryOpen(Catalogue.categoryOf(name));
        boolean buyable = settings.shopEnabled() && open && raw.buy() != null && raw.buy().isPositive()
                && !now.notSold().contains(name);
        boolean sellable = settings.sellingEnabled() && open && raw.sell() != null && raw.sell().isPositive()
                && !now.notBought().contains(name);
        PriceTag.Source source = raw.custom() ? PriceTag.Source.CUSTOM
                : shipped.containsKey(name) ? PriceTag.Source.BASE : PriceTag.Source.RECIPE;
        return raw.tag(name, buyable, sellable, source);
    }

    /** Buy and sell before the gates; either may be null. */
    private record Raw(Money value, Money buy, Money sell, boolean custom) {

        PriceTag tag(String name, boolean buyable, boolean sellable, PriceTag.Source source) {
            return new PriceTag(name, value == null ? Money.ZERO : value, buy == null ? Money.ZERO : buy,
                    sell == null ? Money.ZERO : sell, buyable, sellable, source);
        }
    }

    private Raw raw(String name, Snapshot now) {
        EconomySettings settings = now.settings();
        Money value = now.values().get(name);
        Optional<Money> customBuy = now.buyPrices().of(name);
        Optional<Money> customSell = now.sellPrices().of(name);
        if (value == null && customBuy.isEmpty() && customSell.isEmpty()) {
            return null;
        }
        double multiplier = multiplier(name);
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
        boolean custom = customBuy.isPresent() || customSell.isPresent() || now.custom().contains(name);
        return new Raw(value, buy, sell, custom);
    }

    /**
     * An enchanted book's tag: priced like any item, sold when the shop sells books of it (not a curse, treasure
     * only when allowed, not closed), bought when enchanted things are bought and plain books are. Never paying
     * more for a level than the shop's own lower books, combined on an anvil, would cost.
     */
    private PriceTag enchantTag(EnchantKey key, Snapshot now) {
        String name = key.name();
        EconomySettings settings = now.settings();
        EnchantKind kind = now.enchantments().get(key.enchantment());
        Raw raw = raw(name, now);
        if (raw == null || kind == null || key.level() > Math.max(1, kind.maxLevel())) {
            return PriceTag.unpriced(name);
        }
        // Never cheaper than the plain book inside sells for: bought, stripped and the bare book sold, it would pay.
        Money buy = raw.buy();
        PriceTag plain = tag(BOOK);
        if (buy != null && plain.sellable() && !buy.isMoreThan(plain.sell())) {
            buy = plain.sell().plus(Money.of(1));
        }
        Money sell = raw.sell() == null || buy == null ? raw.sell() : trade.capSellBelowBuy(raw.sell(), buy);
        raw = new Raw(raw.value(), buy, sell, raw.custom());
        boolean buyable = enchantBuyable(kind, name, raw, now);
        if (sell != null && key.level() > 1) {
            int levels = key.level();
            long[] lowerBuy = new long[levels];
            boolean[] open = new boolean[levels];
            long[] anvil = new long[levels];
            for (int level = 1; level < levels; level++) {
                String lower = kind.at(level).name();
                Raw below = raw(lower, now);
                open[level - 1] = below != null && enchantBuyable(kind, lower, below, now);
                lowerBuy[level - 1] = open[level - 1] ? below.buy().minor() : Long.MAX_VALUE;
                anvil[level] = enchantCost.anvil(level + 1, now.pointValue());
            }
            long most = enchantCost.sellCaps(lowerBuy, open, anvil)[levels - 1];
            if (sell.minor() > most) {
                sell = Money.of(Math.max(0, most));
            }
        }
        boolean sellable = !kind.curse() && settings.sellingEnabled() && settings.enchantedSelling()
                && sell != null && sell.isPositive() && !now.notBought().contains(name) && plain.sellable();
        return new Raw(raw.value(), raw.buy(), sell, raw.custom()).tag(name, buyable, sellable,
                raw.custom() ? PriceTag.Source.CUSTOM : PriceTag.Source.RECIPE);
    }

    private boolean enchantBuyable(EnchantKind kind, String name, Raw raw, Snapshot now) {
        EconomySettings settings = now.settings();
        return settings.shopEnabled() && settings.enchantBooks() && raw.buy() != null && raw.buy().isPositive()
                && enchantValue.offered(kind, settings.enchantTreasure(), settings.enchantClosed())
                && !now.notSold().contains(name);
    }

    /**
     * What supply and demand does to an item's price right now: below 1 when it has been sold a lot, above
     * when bought a lot. Always 1 with supply and demand off, and for an item the owner priced by hand.
     */
    public double multiplier(String material) {
        String name = EnchantKey.parse(material).map(EnchantKey::name).orElse(material.toUpperCase(Locale.ROOT));
        Snapshot now = snapshot;
        EconomySettings settings = now.settings();
        if (!settings.dynamicPrices() || now.buyPrices().of(name).isPresent() || now.sellPrices().of(name).isPresent()) {
            return 1.0;
        }
        return market.multiplier(pressure.applyAsDouble(name), settings.lowestMultiplier(), settings.highestMultiplier());
    }

    /** Every item in a category the shop will buy or sell, alphabetically. */
    public List<String> tradableIn(Category category) {
        Snapshot now = snapshot;
        Set<String> candidates = new TreeSet<>(now.values().keySet());
        candidates.addAll(now.buyPrices().amounts().keySet());
        candidates.addAll(now.sellPrices().amounts().keySet());
        return candidates.stream()
                .filter(name -> !name.endsWith(EGG) && !EnchantKey.isKey(name) && Catalogue.categoryOf(name) == category)
                .filter(name -> tag(name).tradable())
                .toList();
    }

    /** Every spawn egg the shop sells or buys, alphabetically — the Spawn eggs drawer. */
    public List<String> tradableEggs() {
        return snapshot.eggs().stream().filter(name -> tag(name).tradable()).toList();
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
        return EnchantKey.isKey(material) ? 1 : Math.max(1, stackSize.applyAsInt(material));
    }

    /**
     * Every enchanted book the shop sells or buys — the Enchantments drawer: the most useful enchantments
     * first, each from level I up.
     */
    public List<String> tradableEnchants() {
        return enchantKeys().stream().filter(name -> tag(name).tradable()).toList();
    }

    /** Every enchanted book there is, traded or not, in the drawer's order. */
    public List<String> enchantKeys() {
        Snapshot now = snapshot;
        return now.enchantments().values().stream()
                .sorted(Comparator.<EnchantKind>comparingDouble(kind -> -now.worth().best(kind.id(), kind.maxLevel(),
                        kind.treasure(), kind.curse())).thenComparing(EnchantKind::id))
                .flatMap(kind -> java.util.stream.IntStream.rangeClosed(1, Math.max(1, kind.maxLevel()))
                        .mapToObj(level -> kind.at(level).name()))
                .toList();
    }

    /** What a curse takes off anything it is on, before the sell ratio: its book's value over a plain book's. */
    public Money cursePart(EnchantKey key) {
        Snapshot now = snapshot;
        EnchantKind kind = now.enchantments().get(key.enchantment());
        if (kind == null || !kind.curse()) {
            return Money.ZERO;
        }
        Money value = now.values().get(new EnchantKey(key.enchantment(), Math.min(key.level(), kind.maxLevel())).name());
        return value == null ? Money.ZERO : value.minus(now.values().getOrDefault(BOOK, Money.ZERO)).max(Money.ZERO);
    }

    /** The anvil's levels for combining two books into {@code level}, in money at today's experience price. */
    public long anvil(int level) {
        return enchantCost.anvil(level, snapshot.pointValue());
    }

    /** The enchantment as this server knows it, if it does. */
    public Optional<EnchantKind> enchantment(String id) {
        return Optional.ofNullable(snapshot.enchantments().get(new EnchantKey(id, 1).enchantment()));
    }
}
