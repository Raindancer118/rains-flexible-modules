package de.raindancer.modules.economy.service;

import de.raindancer.core.content.items.NotForSpawners;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.choose.Catalogue;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.model.PriceTag;
import de.raindancer.modules.economy.model.PricedNames;
import de.raindancer.modules.economy.model.RecipeShape;
import de.raindancer.modules.economy.model.SaleLot;
import de.raindancer.modules.economy.model.TransactionKind;
import de.raindancer.modules.economy.rules.PersonalPriceRule;
import de.raindancer.modules.economy.rules.BulkRule;
import de.raindancer.modules.economy.model.Bulk;
import de.raindancer.core.ui.choose.ItemSelection;
import de.raindancer.modules.economy.rules.TradePriceRule;
import de.raindancer.modules.economy.model.YourPrice;
import de.raindancer.core.social.economy.PersonalPrice;
import de.raindancer.core.social.economy.PriceModifiers;
import de.raindancer.core.social.economy.SaleStops;
import de.raindancer.modules.economy.store.CashTags;
import de.raindancer.modules.economy.store.MarketBook;
import de.raindancer.modules.economy.store.PriceBook;
import de.raindancer.modules.economy.store.RecipeReader;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Buying from and selling to the server.
 *
 * <p>Buying checks the room before it charges; selling takes the items before it pays, and puts them back
 * if the payment cannot land. Only plain items sell — no names, no enchantments, no damage — so a renamed
 * or worn item is never valued as a new one, and cash is never sold as its material.
 */
public final class ShopService implements IEconomyService {

    private final Server server;
    private final RainEconomy economy;
    private final PriceBook prices;
    private final MarketBook market;
    private final Messages messages;
    private final Effects effects;
    private final SettingsStore<EconomySettings> store;
    private final TradePriceRule trade = new TradePriceRule();
    private final PersonalPriceRule personal = new PersonalPriceRule();
    private final BulkRule bulk = new BulkRule();
    private volatile BulkSetup bulkSetup = new BulkSetup(null, ItemSelection.NOTHING, List.of());
    private final de.raindancer.modules.economy.rules.EnchantValueRule enchants =
            new de.raindancer.modules.economy.rules.EnchantValueRule();
    private final Supplier<List<RecipeShape>> recipes;
    private volatile EconomySettings settings;

    private volatile SupplyService supply;

    /** The money supply's settings; the shipped ones, which change nothing, until wired. */
    public void supply(SupplyService service) {
        this.supply = service;
    }

    private de.raindancer.modules.economy.SupplySettings supplied() {
        return SupplyService.settingsOf(supply);
    }
    private volatile List<RecipeShape> knownRecipes = List.of();

    public ShopService(Server server, RainEconomy economy, PriceBook prices, MarketBook market, Messages messages,
                       Effects effects, SettingsStore<EconomySettings> store, EconomySettings settings) {
        this.server = server;
        this.economy = economy;
        this.prices = prices;
        this.market = market;
        this.messages = messages;
        this.effects = effects;
        this.store = store;
        this.recipes = () -> {
            List<RecipeShape> all = new ArrayList<>(RecipeReader.read(server.recipeIterator()));
            all.addAll(RecipeReader.inWorld(itemNames()));
            return all;
        };
        this.settings = settings == null ? EconomySettings.DEFAULTS : settings;
    }

    @Override
    public void settings(EconomySettings updated) {
        this.settings = updated == null ? EconomySettings.DEFAULTS : updated;
        prices.recompute(this.settings, knownRecipes, spawnEggs());
    }

    /** Every spawn egg this server knows. */
    private static List<String> spawnEggs() {
        return itemNames().stream().filter(name -> name.endsWith("_SPAWN_EGG")).toList();
    }

    private static List<String> itemNames() {
        List<String> names = new ArrayList<>();
        for (Material material : Material.values()) {
            if (!material.isLegacy() && material.isItem()) {
                names.add(material.name());
            }
        }
        return names;
    }

    /** Reads the server's recipes again and reprices everything. On the global thread. */
    public int reprice() {
        knownRecipes = recipes.get();
        prices.recompute(settings, knownRecipes, spawnEggs());
        return knownRecipes.size();
    }

    public PriceBook prices() {
        return prices;
    }

    public PriceTag tag(Material material) {
        PriceTag tag = prices.tag(material.name());
        return tag.buyable() && SaleStops.reason(material.name()).isPresent() ? tag.notSold() : tag;
    }

    /** Why the shop does not sell this right now, though it normally would — a server goal collecting it. */
    public Optional<String> saleStopped(Material material) {
        return prices.tag(material.name()).buyable() ? SaleStops.reason(material.name()) : Optional.empty();
    }

    /** What this player pays and is paid for one — the shop's price, changed by their role or the like. */
    public YourPrice priceFor(UUID player, Material material) {
        YourPrice price = personal.forPlayer(player, tag(material), bulkFor(material.name()));
        int lever = de.raindancer.core.social.economy.EconomyLevers.faucetPercent(SELL_SOURCE);
        int again = soldAgainPercent(player, material);
        return price.sellingChanged(lever, "Economy").sellingChanged(again, "Sold lately");
    }

    /** Each change between the shop's sell price and this player's, for the lines under "Sell:". */
    public de.raindancer.modules.economy.model.SellBreakdown sellBreakdown(UUID player, Material material) {
        PriceTag tag = tag(material);
        if (!tag.sellable()) {
            return de.raindancer.modules.economy.model.SellBreakdown.NONE;
        }
        YourPrice own = personal.forPlayer(player, tag, bulkFor(material.name()));
        int lever = de.raindancer.core.social.economy.EconomyLevers.faucetPercent(SELL_SOURCE);
        int again = soldAgainPercent(player, material);
        de.raindancer.modules.economy.SupplySettings live = supplied();
        int stacks = again == 0 ? 0 : sold.soldLately(player, material.name(), live.diminishingMinutes())
                / Math.max(1, prices.stackSizeOf(material.name()));
        YourPrice yours = own.sellingChanged(lever, "Economy").sellingChanged(again, "Sold lately");
        Money uncapped = de.raindancer.core.social.economy.PriceModifiers.scale(tag.sell(),
                yours.sellChange().percent(), de.raindancer.core.social.economy.TradeSide.SELL);
        Optional<Money> left = Optional.empty();
        Money mine = live.sellBudgets()
                ? de.raindancer.modules.economy.SupplySettings.money(live.sellBudgetPerPlayer(), settings.currency())
                : Money.ZERO;
        if (mine.isPositive()) {
            left = Optional.of(mine.minus(economy.book().today(player, SELL_SOURCE)).max(Money.ZERO));
        }
        return new de.raindancer.modules.economy.model.SellBreakdown(own.sellChange().percent(),
                own.sellChange().reasons(), lever, again, stacks, live.diminishingMinutes(),
                uncapped.isMoreThan(yours.sell()), left);
    }

    static final String SELL_SOURCE = de.raindancer.modules.economy.model.Sources.SELL;
    private final SaleMemory sold = new SaleMemory(System::currentTimeMillis);

    /** How much less selling this again pays: a stack's worth sold lately is one step down. Zero when switched off. */
    int soldAgainPercent(UUID player, Material material) {
        de.raindancer.modules.economy.SupplySettings live = supplied();
        if (!live.diminishing() || live.diminishingPercent() <= 0) {
            return 0;
        }
        int stacks = sold.soldLately(player, material.name(), live.diminishingMinutes())
                / Math.max(1, prices.stackSizeOf(material.name()));
        long factor = new de.raindancer.modules.economy.rules.DiminishingRule()
                .payout(Money.of(10_000), stacks, live.diminishingPercent()).minor();
        return (int) Math.floorDiv(factor - 10_000, 100);
    }

    /** Why the shop will not pay {@code total} more to this player today, if it will not: a message key and what is left. */
    Optional<Map.Entry<String, Money>> overBudget(Player player, Money total) {
        de.raindancer.modules.economy.SupplySettings live = supplied();
        if (!live.sellBudgets()) {
            return Optional.empty();
        }
        Currency currency = settings.currency();
        Money mine = de.raindancer.modules.economy.SupplySettings.money(live.sellBudgetPerPlayer(), currency);
        if (mine.isPositive()) {
            Money left = mine.minus(economy.book().today(player.getUniqueId(), SELL_SOURCE)).max(Money.ZERO);
            if (total.isMoreThan(left)) {
                return Optional.of(Map.entry("economy.shop.budget-yours", left));
            }
        }
        Money all = de.raindancer.modules.economy.SupplySettings.money(live.sellBudgetServer(), currency);
        if (all.isPositive()) {
            Money left = all.minus(economy.book().todayByAll(SELL_SOURCE)).max(Money.ZERO);
            if (total.isMoreThan(left)) {
                return Optional.of(Map.entry("economy.shop.budget-server", left));
            }
        }
        return Optional.empty();
    }

    public void forget(UUID player) {
        sold.forget(player);
    }

    /** How much cheaper this item gets bought in quantity, as the owner set it. */
    public Bulk bulkFor(String material) {
        EconomySettings live = settings;
        if (!live.bulkDiscount()) {
            return Bulk.NONE;
        }
        BulkSetup setup = bulkSetup;
        if (setup.from() != live) {
            setup = new BulkSetup(live, ItemSelection.parse(live.bulkItems()), BulkRule.tiers(live.bulkTiers()));
            bulkSetup = setup;
        }
        return bulk.forItem(material, setup.items(), setup.tiers(), live.bulkMostPercent());
    }

    /** The owner's bulk lists, read once per settings change rather than once per price drawn. */
    private record BulkSetup(EconomySettings from, ItemSelection items, List<Bulk.Tier> tiers) {
    }

    /** What this player pays for an enchanted book the shop offers at {@code price}. */
    public PersonalPrice enchantPriceFor(UUID player, EnchantOffer offer) {
        return PriceModifiers.buy(player, Material.ENCHANTED_BOOK.name(), offer.price());
    }

    // ---------------------------------------------------------------------------- buying

    public void buy(Player player, Material material, int quantity) {
        EconomySettings live = settings;
        Currency currency = live.currency();
        PriceTag tag = tag(material);
        if (!tag.buyable()) {
            Optional<String> stopped = saleStopped(material);
            if (stopped.isPresent()) {
                refuse(player, "economy.shop.sale-stopped", "item", Catalogue.readable(material.name()),
                        "reason", stopped.get());
            } else {
                refuse(player, "economy.shop.not-for-sale", "item", Catalogue.readable(material.name()));
            }
            return;
        }
        int amount = Math.max(1, quantity);
        Optional<Money> total = priceFor(player.getUniqueId(), material).buyFor(amount);
        if (total.isEmpty()) {
            refuse(player, "economy.not-an-amount");
            return;
        }
        List<ItemStack> stacks = sold(stacksOf(material, amount));
        if (!CashService.fits(player.getInventory(), stacks)) {
            refuse(player, "economy.shop.no-room");
            return;
        }
        String what = amount + " × " + Catalogue.readable(material.name());
        EconomyResult result = economy.move(player.getUniqueId(), total.get().negate(), TransactionKind.BUY, what);
        if (!result.succeeded()) {
            Outcomes.tell(messages, effects, player, EconomyResult.failed(result.outcome(), total.get(),
                    result.balance()), currency, "");
            return;
        }
        player.getInventory().addItem(stacks.toArray(ItemStack[]::new)).values()
                .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        if (live.dynamicPrices() && tag.source() != PriceTag.Source.CUSTOM) {
            market.traded(material.name(), amount, prices.stackSizeOf(material.name()),
                    live.pressurePerStackClamped(), true, live.recoveryHoursClamped());
        }
        effects.play(player.getUniqueId(), Cues.REWARD);
        messages.send(player, "economy.shop.bought", "count", String.valueOf(amount),
                "item", Catalogue.readable(material.name()), "amount", currency.render(total.get()));
    }

    /** One enchanted book the shop sells. */
    public record EnchantOffer(org.bukkit.enchantments.Enchantment enchantment, int level, Money price) {
    }

    /** Every enchanted book on sale: each enchantment the server knows, each level up to its normal maximum. */
    public List<EnchantOffer> enchantOffers() {
        EconomySettings live = settings;
        if (!live.enchantBooks()) {
            return List.of();
        }
        Money book = tag(Material.BOOK).buy();
        List<EnchantOffer> offers = new ArrayList<>();
        var registry = io.papermc.paper.registry.RegistryAccess.registryAccess()
                .getRegistry(io.papermc.paper.registry.RegistryKey.ENCHANTMENT);
        List<org.bukkit.enchantments.Enchantment> all = new ArrayList<>();
        registry.forEach(all::add);
        var worth = live.enchantWorthTable();
        // Most useful first, so the drawer opens on what players come for.
        all.sort(java.util.Comparator.<org.bukkit.enchantments.Enchantment>comparingDouble(enchantment -> -worth.best(
                        enchantment.getKey().getKey(), enchantment.getMaxLevel(), isTreasure(enchantment), enchantment.isCursed()))
                .thenComparing(enchantment -> enchantment.getKey().getKey()));
        for (org.bukkit.enchantments.Enchantment enchantment : all) {
            for (int level = 1; level <= enchantment.getMaxLevel(); level++) {
                var each = new de.raindancer.modules.economy.model.EnchantLevel(enchantment.getKey().getKey(), level,
                        enchantment.getMaxLevel(), isTreasure(enchantment), enchantment.isCursed());
                if (enchants.offered(each, live.enchantTreasure(), live.enchantClosed())) {
                    offers.add(new EnchantOffer(enchantment, level,
                            enchants.buyPrice(book, each, live.enchantPriceMoney(), live.enchantValueMoney(), worth)));
                }
            }
        }
        return offers;
    }

    /** Sells one enchanted book: paid first, then handed over — the same order as anything else bought here. */
    public void buyEnchant(Player player, EnchantOffer offer) {
        Currency currency = settings.currency();
        boolean stillOffered = enchantOffers().stream().anyMatch(each -> each.enchantment().equals(offer.enchantment())
                && each.level() == offer.level() && each.price().equals(offer.price()));
        if (!stillOffered) {
            refuse(player, "economy.shop.enchant-gone");
            return;
        }
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        book.editMeta(org.bukkit.inventory.meta.EnchantmentStorageMeta.class,
                meta -> meta.addStoredEnchant(offer.enchantment(), offer.level(), false));
        if (!CashService.fits(player.getInventory(), List.of(book))) {
            refuse(player, "economy.shop.no-room");
            return;
        }
        String name = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(offer.enchantment().displayName(offer.level()));
        Money price = enchantPriceFor(player.getUniqueId(), offer).price();
        EconomyResult result = economy.move(player.getUniqueId(), price.negate(), TransactionKind.BUY,
                "Enchanted book: " + name);
        if (!result.succeeded()) {
            Outcomes.tell(messages, effects, player, EconomyResult.failed(result.outcome(), price,
                    result.balance()), currency, "");
            return;
        }
        player.getInventory().addItem(book).values()
                .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        effects.play(player.getUniqueId(), Cues.REWARD);
        messages.send(player, "economy.shop.bought", "count", "1", "item", "Enchanted Book (" + name + ")",
                "amount", currency.render(price));
    }

    /** What the shop hands over: a spawn egg it sold hatches a mob but never sets a spawner. */
    private static List<ItemStack> sold(List<ItemStack> stacks) {
        stacks.forEach(stack -> {
            if (stack.getType().name().endsWith("_SPAWN_EGG")) {
                NotForSpawners.mark(stack);
            }
        });
        return stacks;
    }

    private static List<ItemStack> stacksOf(Material material, int amount) {
        List<ItemStack> stacks = new ArrayList<>();
        int max = Math.max(1, material.getMaxStackSize());
        int left = amount;
        while (left > 0) {
            int part = Math.min(max, left);
            stacks.add(new ItemStack(material, part));
            left -= part;
        }
        return stacks;
    }

    /** What a pack's contents push on supply and demand: the same as buying them one by one. */
    public void boughtInPack(List<de.raindancer.modules.economy.model.PackItem> contents) {
        EconomySettings live = settings;
        if (!live.dynamicPrices()) {
            return;
        }
        for (var each : contents) {
            if (prices.tag(each.material()).source() != PriceTag.Source.CUSTOM) {
                market.traded(each.material(), each.amount(), prices.stackSizeOf(each.material()),
                        live.pressurePerStackClamped(), true, live.recoveryHoursClamped());
            }
        }
    }

    // ---------------------------------------------------------------------------- selling

    /** How many plain items of this kind a player carries. */
    public int carrying(Player player, Material material) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (sellableStack(stack, material)) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    static boolean sellableStack(ItemStack stack, Material material) {
        return stack != null && stack.getType() == material && !CashTags.isCash(stack)
                && (stack.isSimilar(new ItemStack(material))
                || stack.isSimilar(NotForSpawners.mark(new ItemStack(material))));
    }

    /** Sells up to {@code quantity}; fewer if fewer are carried. */
    public void sell(Player player, Material material, int quantity) {
        sellMany(player, Map.of(material, quantity), false);
    }

    public void sellHand(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            refuse(player, "economy.shop.nothing-in-hand");
            return;
        }
        Optional<SaleLot> lot = appraise(player.getUniqueId(), held, player.getInventory().getHeldItemSlot());
        if (lot.isPresent() && !lot.get().plain()) {
            sellLot(player, lot.get());
        } else {
            sell(player, held.getType(), held.getAmount());
        }
    }

    /**
     * Sells the enchantments off the item in hand and keeps the item, the way a grindstone takes them off —
     * at what they would add to its sale. Curses stay on; a book with nothing left becomes a plain book.
     */
    public void sellEnchantments(Player player) {
        EconomySettings live = settings;
        PlayerInventory inventory = player.getInventory();
        int slot = inventory.getHeldItemSlot();
        ItemStack held = inventory.getItem(slot);
        if (held == null || held.getType().isAir()) {
            refuse(player, "economy.shop.nothing-in-hand");
            return;
        }
        if (!live.enchantedSelling() || CashTags.isCash(held) || de.raindancer.core.content.items.NonIngredients.isMarked(held)
                || de.raindancer.core.content.items.InsuredItems.isInsured(held) || !onlyWornOrEnchanted(held)) {
            refuse(player, "economy.shop.no-enchantments");
            return;
        }
        org.bukkit.inventory.meta.ItemMeta meta = held.getItemMeta();
        java.util.Map<org.bukkit.enchantments.Enchantment, Integer> all = new java.util.HashMap<>(meta.getEnchants());
        if (meta instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta stored) {
            all.putAll(stored.getStoredEnchants());
        }
        java.util.List<de.raindancer.modules.economy.model.EnchantLevel> levels = new ArrayList<>();
        all.forEach((enchantment, level) -> levels.add(new de.raindancer.modules.economy.model.EnchantLevel(
                enchantment.getKey().getKey(), level, enchantment.getMaxLevel(), isTreasure(enchantment), enchantment.isCursed())));
        Money each = enchants.enchantsOff(levels, live.enchantValueMoney(), live.enchantWorthTable(), live.sellRatioClamped());
        if (!each.isPositive()) {
            refuse(player, "economy.shop.no-enchantments");
            return;
        }
        Optional<Money> total = trade.total(each, held.getAmount());
        if (total.isEmpty() || !total.get().isPositive()) {
            refuse(player, "economy.shop.no-enchantments");
            return;
        }
        Optional<Map.Entry<String, Money>> budget = overBudget(player, total.get());
        if (budget.isPresent()) {
            refuse(player, budget.get().getKey(), "left", live.currency().render(budget.get().getValue()));
            return;
        }
        ItemStack before = held.clone();
        ItemStack after = held.clone();
        after.editMeta(edited -> {
            all.keySet().stream().filter(enchantment -> !enchantment.isCursed()).forEach(edited::removeEnchant);
            if (edited instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta stored) {
                all.keySet().stream().filter(enchantment -> !enchantment.isCursed()).forEach(stored::removeStoredEnchant);
            }
        });
        if (after.getType() == Material.ENCHANTED_BOOK && after.getItemMeta() instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta left
                && !left.hasStoredEnchants()) {
            after = after.withType(Material.BOOK);
        }
        inventory.setItem(slot, after);
        String label = Catalogue.readable(before.getType().name());
        EconomyResult result = economy.move(player.getUniqueId(), total.get(), TransactionKind.SELL,
                label + " (enchantments)", SELL_SOURCE);
        if (!result.succeeded()) {
            inventory.setItem(slot, before);
            Outcomes.tell(messages, effects, player, result, live.currency(), "");
            return;
        }
        effects.play(player.getUniqueId(), Cues.EARNED);
        messages.send(player, "economy.shop.sold-enchantments", "what", label, "amount", live.currency().render(total.get()));
    }

    /**
     * What one stack fetches, if the shop takes it: plain items by kind, and — when enchanted selling is on —
     * enchanted or worn ones one at a time, for more or for less. A renamed item is valued as itself; an
     * item carrying anything else (lore, plugin data, contents) is not bought at all.
     */
    public Optional<SaleLot> appraise(UUID seller, ItemStack stack, int slot) {
        if (stack == null || stack.getType().isAir() || CashTags.isCash(stack)
                || de.raindancer.core.content.items.NonIngredients.isMarked(stack)
                || de.raindancer.core.content.items.InsuredItems.isInsured(stack)) {
            return Optional.empty();
        }
        Material material = stack.getType();
        if (sellableStack(stack, material)) {
            PriceTag tag = tag(material);
            return tag.sellable()
                    ? priceFor(seller, material).sellFor(stack.getAmount()).map(total -> new SaleLot(material, -1,
                    stack.getAmount(), total, Catalogue.readable(material.name())))
                    : Optional.empty();
        }
        EconomySettings live = settings;
        if (!live.enchantedSelling() || !onlyWornOrEnchanted(stack)) {
            return Optional.empty();
        }
        PriceTag tag = tag(material == Material.ENCHANTED_BOOK ? Material.BOOK : material);
        if (!tag.sellable() || !live.sellingEnabled()) {
            return Optional.empty();
        }
        java.util.List<de.raindancer.modules.economy.model.EnchantLevel> levels = new ArrayList<>();
        org.bukkit.inventory.meta.ItemMeta meta = stack.getItemMeta();
        java.util.Map<org.bukkit.enchantments.Enchantment, Integer> all = new java.util.HashMap<>(meta.getEnchants());
        if (meta instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta stored) {
            all.putAll(stored.getStoredEnchants());
        }
        all.forEach((enchantment, level) -> levels.add(new de.raindancer.modules.economy.model.EnchantLevel(
                enchantment.getKey().getKey(), level, enchantment.getMaxLevel(), isTreasure(enchantment), enchantment.isCursed())));
        int damage = meta instanceof org.bukkit.inventory.meta.Damageable worn ? worn.getDamage() : 0;
        Money each = enchants.sellValue(tag.sell(), enchants.durabilityLeft(material.getMaxDurability(), damage),
                enchants.bonus(levels, live.enchantValueMoney(), live.enchantWorthTable()), live.sellRatioClamped());
        if (!each.isPositive()) {
            return Optional.empty();
        }
        String label = Catalogue.readable(material.name()) + (levels.isEmpty() ? " (worn)" : " (enchanted)");
        return trade.total(each, stack.getAmount()).map(total -> new SaleLot(material, slot, stack.getAmount(), total,
                label));
    }

    /** Whether the only differences from a plain item are enchantments, wear, a name and the anvil's cost. */
    private static boolean onlyWornOrEnchanted(ItemStack stack) {
        ItemStack stripped = stripped(stack);
        // The fresh item goes through the same round trip: a book whose enchantments were taken off keeps an
        // empty enchantment list in its data, which a brand-new book does not have — and is just as plain.
        return stripped != null && stripped.isSimilar(stripped(new ItemStack(stack.getType())));
    }

    /** A copy without enchantments, wear, repair cost and name — or null for an item without meta. */
    private static ItemStack stripped(ItemStack stack) {
        ItemStack stripped = stack.clone();
        org.bukkit.inventory.meta.ItemMeta meta = stripped.getItemMeta();
        if (meta == null) {
            return null;
        }
        new ArrayList<>(meta.getEnchants().keySet()).forEach(meta::removeEnchant);
        if (meta instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta stored) {
            new ArrayList<>(stored.getStoredEnchants().keySet()).forEach(stored::removeStoredEnchant);
        }
        if (meta instanceof org.bukkit.inventory.meta.Damageable worn) {
            worn.setDamage(0);
        }
        if (meta instanceof org.bukkit.inventory.meta.Repairable repairable) {
            repairable.setRepairCost(0);
        }
        meta.displayName(null);
        stripped.setItemMeta(meta);
        stripped.setAmount(1);
        return stripped;
    }

    /** Everything carried that the shop takes: plain items grouped by kind, special ones one by one. */
    public List<SaleLot> lots(Player player) {
        Map<Material, Integer> plain = new LinkedHashMap<>();
        List<SaleLot> special = new ArrayList<>();
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            Optional<SaleLot> lot = appraise(player.getUniqueId(), stack, slot);
            if (lot.isEmpty()) {
                continue;
            }
            if (lot.get().plain()) {
                plain.merge(stack.getType(), stack.getAmount(), Integer::sum);
            } else {
                special.add(lot.get());
            }
        }
        List<SaleLot> all = new ArrayList<>();
        plain.forEach((material, count) -> priceFor(player.getUniqueId(), material).sellFor(count)
                .ifPresent(total ->
                all.add(new SaleLot(material, -1, count, total, Catalogue.readable(material.name())))));
        all.addAll(special);
        return all;
    }

    /** Everything carried that the shop takes, in one go. */
    public void sellEverything(Player player) {
        List<SaleLot> all = lots(player);
        if (all.isEmpty()) {
            refuse(player, "economy.shop.nothing-to-sell");
            return;
        }
        Map<Material, Integer> plain = new LinkedHashMap<>();
        for (SaleLot lot : all) {
            if (lot.plain()) {
                plain.put(lot.material(), lot.count());
            } else {
                sellLot(player, lot);
            }
        }
        if (!plain.isEmpty()) {
            sellMany(player, plain, true);
        }
    }

    /** Sells one enchanted or worn item, if it is still the item that was appraised. */
    public void sellLot(Player player, SaleLot lot) {
        if (lot.plain()) {
            sell(player, lot.material(), lot.count());
            return;
        }
        PlayerInventory inventory = player.getInventory();
        ItemStack there = inventory.getItem(lot.slot());
        Optional<SaleLot> now = appraise(player.getUniqueId(), there, lot.slot());
        if (now.isEmpty() || now.get().plain() || !now.get().total().equals(lot.total())) {
            refuse(player, "economy.shop.changed");
            return;
        }
        Optional<Map.Entry<String, Money>> budget = overBudget(player, lot.total());
        if (budget.isPresent()) {
            refuse(player, budget.get().getKey(), "left", settings.currency().render(budget.get().getValue()));
            return;
        }
        ItemStack taken = there.clone();
        inventory.setItem(lot.slot(), null);
        Currency currency = settings.currency();
        EconomyResult result = economy.move(player.getUniqueId(), lot.total(), TransactionKind.SELL, lot.label(),
                SELL_SOURCE);
        if (!result.succeeded()) {
            inventory.addItem(taken).values()
                    .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
            Outcomes.tell(messages, effects, player, result, currency, "");
            return;
        }
        effects.play(player.getUniqueId(), Cues.EARNED);
        messages.send(player, "economy.shop.sold", "count", String.valueOf(lot.count()), "what", lot.label(),
                "amount", currency.render(lot.total()));
    }

    private void sellMany(Player player, Map<Material, Integer> wanted, boolean everything) {
        EconomySettings live = settings;
        Currency currency = live.currency();
        Money total = Money.ZERO;
        int items = 0;
        Map<Material, Integer> taken = new LinkedHashMap<>();
        PlayerInventory inventory = player.getInventory();
        for (Map.Entry<Material, Integer> each : wanted.entrySet()) {
            Material material = each.getKey();
            PriceTag tag = tag(material);
            if (!tag.sellable()) {
                if (!everything) {
                    refuse(player, "economy.shop.not-bought", "item", Catalogue.readable(material.name()));
                }
                continue;
            }
            int removed = take(inventory, material, Math.max(1, each.getValue()));
            if (removed == 0) {
                if (!everything) {
                    refuse(player, "economy.shop.none-carried", "item", Catalogue.readable(material.name()));
                }
                continue;
            }
            Optional<Money> worth = priceFor(player.getUniqueId(), material).sellFor(removed);
            if (worth.isEmpty()) {
                giveBack(player, material, removed);
                continue;
            }
            total = total.plus(worth.get());
            items += removed;
            taken.put(material, removed);
        }
        if (taken.isEmpty()) {
            return;
        }
        String what = taken.size() == 1
                ? items + " × " + Catalogue.readable(taken.keySet().iterator().next().name())
                : items + " items";
        Optional<Map.Entry<String, Money>> budget = overBudget(player, total);
        if (budget.isPresent()) {
            taken.forEach((material, count) -> giveBack(player, material, count));
            refuse(player, budget.get().getKey(), "left", currency.render(budget.get().getValue()));
            return;
        }
        EconomyResult result = economy.move(player.getUniqueId(), total, TransactionKind.SELL, what, SELL_SOURCE);
        if (!result.succeeded()) {
            taken.forEach((material, count) -> giveBack(player, material, count));
            Outcomes.tell(messages, effects, player, result, currency, "");
            return;
        }
        if (supplied().diminishing() && supplied().diminishingPercent() > 0) {
            taken.forEach((material, count) -> sold.sold(player.getUniqueId(), material.name(), count));
        }
        if (live.dynamicPrices()) {
            taken.forEach((material, count) -> {
                if (prices.tag(material.name()).source() != PriceTag.Source.CUSTOM) {
                    market.traded(material.name(), count, prices.stackSizeOf(material.name()),
                            live.pressurePerStackClamped(), false, live.recoveryHoursClamped());
                }
            });
        }
        effects.play(player.getUniqueId(), Cues.EARNED);
        messages.send(player, "economy.shop.sold", "count", String.valueOf(items), "what", what,
                "amount", currency.render(total));
    }

    /** Takes up to this many plain items, last slots first, so the hotbar is emptied last. */
    private static int take(PlayerInventory inventory, Material material, int wanted) {
        ItemStack[] contents = inventory.getStorageContents();
        int left = wanted;
        for (int i = contents.length - 1; i >= 0 && left > 0; i--) {
            ItemStack stack = contents[i];
            if (!sellableStack(stack, material)) {
                continue;
            }
            int moved = Math.min(left, stack.getAmount());
            if (moved == stack.getAmount()) {
                inventory.setItem(i, null);
            } else {
                stack.setAmount(stack.getAmount() - moved);
                inventory.setItem(i, stack);
            }
            left -= moved;
        }
        return wanted - left;
    }

    private static void giveBack(Player player, Material material, int count) {
        player.getInventory().addItem(stacksOf(material, count).toArray(ItemStack[]::new)).values()
                .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
    }

    // ---------------------------------------------------------------------------- owner's prices

    /** Writes one item's custom price into the settings file — the same list an owner edits by hand. */
    public boolean setCustom(String key, Material material, Money price) {
        EconomySettings live = settings;
        List<String> lines = switch (key) {
            case "shop.buy-prices" -> live.buyPrices();
            case "shop.sell-prices" -> live.sellPrices();
            case "shop.values" -> live.customValues();
            default -> null;
        };
        if (lines == null) {
            return false;
        }
        Currency currency = live.currency();
        PricedNames current = PricedNames.parse(lines, currency);
        PricedNames next = price == null ? current.without(material.name()) : current.with(material.name(), price);
        return writeList(key, next.lines(currency));
    }

    /** Puts an item on or off one of the closed lists. */
    public boolean setListed(String key, Material material, boolean listed) {
        EconomySettings live = settings;
        List<String> lines = new ArrayList<>(switch (key) {
            case "shop.not-sold" -> live.notSold();
            case "shop.not-bought" -> live.notBought();
            default -> List.<String>of();
        });
        String name = material.name().toLowerCase(Locale.ROOT);
        lines.removeIf(line -> line.strip().equalsIgnoreCase(name));
        if (listed) {
            lines.add(name);
        }
        return writeList(key, lines);
    }

    private boolean writeList(String key, List<String> lines) {
        boolean set = store.set(key, String.join(", ", lines));
        if (set) {
            store.trySave();
        }
        return set;
    }

    private void refuse(Player player, String key, Object... values) {
        messages.send(player, key, values);
        effects.play(player.getUniqueId(), Cues.NO);
    }

    /** Treasure is a tag since 26.3 — what loot and trades give, never an enchanting table. */
    private static boolean isTreasure(org.bukkit.enchantments.Enchantment enchantment) {
        var registry = io.papermc.paper.registry.RegistryAccess.registryAccess()
                .getRegistry(io.papermc.paper.registry.RegistryKey.ENCHANTMENT);
        return registry.getTag(io.papermc.paper.registry.keys.tags.EnchantmentTagKeys.TREASURE)
                .contains(io.papermc.paper.registry.TypedKey.create(
                        io.papermc.paper.registry.RegistryKey.ENCHANTMENT, enchantment.getKey()));
    }
}
