package de.raindancer.modules.economy.service;

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
import de.raindancer.modules.economy.model.TransactionKind;
import de.raindancer.modules.economy.rules.TradePriceRule;
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
    private final Supplier<List<RecipeShape>> recipes;
    private volatile EconomySettings settings;
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
        this.recipes = () -> RecipeReader.read(server.recipeIterator());
        this.settings = settings == null ? EconomySettings.DEFAULTS : settings;
    }

    @Override
    public void settings(EconomySettings updated) {
        this.settings = updated == null ? EconomySettings.DEFAULTS : updated;
        prices.recompute(this.settings, knownRecipes);
    }

    /** Reads the server's recipes again and reprices everything. On the global thread. */
    public int reprice() {
        knownRecipes = recipes.get();
        prices.recompute(settings, knownRecipes);
        return knownRecipes.size();
    }

    public PriceBook prices() {
        return prices;
    }

    public PriceTag tag(Material material) {
        return prices.tag(material.name());
    }

    // ---------------------------------------------------------------------------- buying

    public void buy(Player player, Material material, int quantity) {
        EconomySettings live = settings;
        Currency currency = live.currency();
        PriceTag tag = tag(material);
        if (!tag.buyable()) {
            refuse(player, "economy.shop.not-for-sale", "item", Catalogue.readable(material.name()));
            return;
        }
        int amount = Math.max(1, quantity);
        Optional<Money> total = trade.total(tag.buy(), amount);
        if (total.isEmpty()) {
            refuse(player, "economy.not-an-amount");
            return;
        }
        List<ItemStack> stacks = stacksOf(material, amount);
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
                && stack.isSimilar(new ItemStack(material));
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
        sell(player, held.getType(), held.getAmount());
    }

    /** Everything carried that the shop takes, in one go. */
    public void sellEverything(Player player) {
        Map<Material, Integer> all = new LinkedHashMap<>();
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && !stack.getType().isAir() && sellableStack(stack, stack.getType())
                    && tag(stack.getType()).sellable()) {
                all.merge(stack.getType(), stack.getAmount(), Integer::sum);
            }
        }
        if (all.isEmpty()) {
            refuse(player, "economy.shop.nothing-to-sell");
            return;
        }
        sellMany(player, all, true);
    }

    /** What everything sellable in this inventory would fetch, per material. */
    public Map<Material, Money> valueOfInventory(Player player) {
        Map<Material, Integer> counts = new LinkedHashMap<>();
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && !stack.getType().isAir() && sellableStack(stack, stack.getType())) {
                counts.merge(stack.getType(), stack.getAmount(), Integer::sum);
            }
        }
        Map<Material, Money> worth = new LinkedHashMap<>();
        counts.forEach((material, count) -> {
            PriceTag tag = tag(material);
            if (tag.sellable()) {
                trade.total(tag.sell(), count).ifPresent(total -> worth.put(material, total));
            }
        });
        return worth;
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
            Optional<Money> worth = trade.total(tag.sell(), removed);
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
        EconomyResult result = economy.move(player.getUniqueId(), total, TransactionKind.SELL, what);
        if (!result.succeeded()) {
            taken.forEach((material, count) -> giveBack(player, material, count));
            Outcomes.tell(messages, effects, player, result, currency, "");
            return;
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
}
