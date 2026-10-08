package de.raindancer.modules.economy.screen;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Every switch of the economy on one page, a click each, written straight to the settings file — and the
 * tools an owner reaches for: repricing, drawing the lottery, calming the market, painting the currency.
 */
public final class AdminMenu extends Menu implements IEconomyScreen {

    private record Switch(String key, String label, Material icon) {
    }

    private static final List<Switch> SWITCHES = List.of(
            new Switch("features.pay", "Paying", Material.WRITABLE_BOOK),
            new Switch("features.bills", "Bills", Material.PAPER),
            new Switch("features.hire", "Hiring", Material.IRON_PICKAXE),
            new Switch("features.cash", "Coins", Material.GOLD_NUGGET),
            new Switch("features.cheques", "Cheques", Material.FILLED_MAP),
            new Switch("cash.right-click", "Right click pays in", Material.HOPPER),
            new Switch("features.shop", "Shop", Material.EMERALD),
            new Switch("features.selling", "Selling", Material.CHEST),
            new Switch("shop.enchanted-selling", "Enchanted items sell", Material.ENCHANTED_BOOK),
            new Switch("features.dynamic-prices", "Supply and demand", Material.COMPARATOR),
            new Switch("shop.derive-from-recipes", "Recipe prices", Material.CRAFTING_TABLE),
            new Switch("features.income", "Passive income", Material.CLOCK),
            new Switch("features.daily", "Daily reward", Material.SUNFLOWER),
            new Switch("features.advancement-rewards", "Advancement rewards", Material.KNOWLEDGE_BOOK),
            new Switch("features.interest", "Interest", Material.EXPERIENCE_BOTTLE),
            new Switch("features.baltop", "Richest players", Material.GOLDEN_HELMET),
            new Switch("accounts.action-bar", "Changes above hotbar", Material.OAK_SIGN),
            new Switch("features.gambling", "Gambling", Material.GOLD_BLOCK),
            new Switch("features.coinflip", "Coin flips", Material.SUNFLOWER),
            new Switch("features.dice", "Dice", Material.WHITE_WOOL),
            new Switch("features.slots", "Slots", Material.DIAMOND),
            new Switch("features.roulette", "Roulette", Material.ENDER_PEARL),
            new Switch("features.lottery", "Lottery", Material.FILLED_MAP));

    private final EconomyServices services;

    public AdminMenu(EconomyServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Run the economy");
    }

    @Override
    public String breadcrumb() {
        return "Economy";
    }

    @Override
    protected void render() {
        if (!viewer.hasPermission(PermissionNodes.ADMIN)) {
            set(MenuLayout.HEADER_SUBJECT, Icons.of(Material.BARRIER, "<red>Staff only"));
            return;
        }
        SettingsStore<EconomySettings> store = services.store();
        EconomySettings live = services.config();
        set(MenuLayout.HEADER_LEFT, Icons.of(Material.GOLD_NUGGET, "<yellow>Paint the currency",
                "<gray>Names, symbol, colours, decimals."), click -> new CurrencyMenu(services, viewer, this).open());
        set(MenuLayout.HEADER_SUBJECT, Icons.of(Material.COMMAND_BLOCK, "<red>Economy",
                "<gray>Every switch writes the settings file at once.",
                "<dark_gray>The rest is in /settings → Economy."));
        set(MenuLayout.HEADER_RIGHT, Icons.of(Material.COMPARATOR, "<white>Sell prices: "
                        + live.sellPricing().name().toLowerCase().replace('_', ' '),
                "<gray>Automatic, or only the custom list.", "<yellow>Click<gray> to switch"), click -> {
            store.cycle("shop.sell-pricing");
            store.trySave();
            refresh();
        });

        for (int i = 0; i < SWITCHES.size(); i++) {
            Switch each = SWITCHES.get(i);
            boolean on = "true".equalsIgnoreCase(store.display(each.key())) || "on".equalsIgnoreCase(store.display(each.key()));
            cell(1 + i / 9, i % 9, Icons.of(on ? each.icon() : Material.GRAY_DYE,
                    (on ? "<green>" : "<red>") + each.label(), on ? "<green>On" : "<red>Off",
                    "<dark_gray>" + each.key(), "<yellow>Click<gray> to switch"), click -> {
                store.cycle(each.key());
                store.trySave();
                refresh();
            });
        }

        toolbar(1, Icons.of(services.cash().coinMaterial(), "<yellow>The coin",
                "<gray>Made of " + services.cash().coinMaterial().name().toLowerCase().replace('_', ' '),
                "<yellow>Click<gray> to pick any item", "<dark_gray>or /eco coin with it in your hand"), click ->
                new de.raindancer.core.ui.choose.ItemChooser(viewer, services.brand(), this, "The coin is made of…",
                        material -> {
                            store.set("cash.coin-item", material.name());
                            store.set("cash.coin-model", "");
                            store.trySave();
                            services.messages().send(viewer, "economy.admin.coin", "item",
                                    material.name().toLowerCase().replace('_', ' '));
                        }).open());
        toolbar(2, Icons.of(Material.CRAFTING_TABLE, "<white>Reprice everything",
                "<gray>Reads every recipe on the server again."), click -> Scheduling.global(services.plugin(), () -> {
            int recipes = services.shop().reprice();
            services.messages().send(viewer, "economy.admin.repriced", "recipes", String.valueOf(recipes));
        }));
        toolbar(4, Icons.of(Material.FILLED_MAP, "<aqua>Draw the lottery now"), click ->
                new ConfirmScreen(viewer, services.brand(), this, "<yellow>Draw the lottery now?",
                        List.of("<gray>The pot is paid out and the next draw starts."), () -> {
                    long next = System.currentTimeMillis() + Math.max(1, services.config().drawHours()) * 3_600_000L;
                    services.lottery().draw(next);
                    open();
                }).open());
        toolbar(6, Icons.of(Material.WATER_BUCKET, "<white>Calm the market",
                "<gray>Every price back to plain, at once."), click ->
                new ConfirmScreen(viewer, services.brand(), this, "<yellow>Calm the market?",
                        List.of("<gray>All supply and demand is forgotten."), () -> {
                    services.market().calm();
                    services.messages().send(viewer, "economy.admin.calmed");
                    open();
                }).open());
    }

    @Override
    public String describe() {
        return "every switch of the economy, and the owner's tools";
    }
}
