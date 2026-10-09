package de.raindancer.modules.economy.screen;

import de.raindancer.core.data.settings.SettingsMenu;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.util.Mini;
import de.raindancer.modules.economy.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * The owner's desk: the switches, the casino, the shop items, a door to each topic of the settings, and the
 * tools an owner reaches for — repricing, drawing the lottery, calming the market, painting the currency.
 */
public final class AdminMenu extends Menu implements IEconomyScreen {

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
        Currency currency = services.currency();
        set(MenuLayout.HEADER_LEFT, Icons.of(Material.GOLD_NUGGET, "<yellow>Paint the currency",
                "<gray>Names, symbol, colours.", "<yellow>Click<gray> to open"),
                click -> new CurrencyMenu(services, viewer, this).open());
        set(MenuLayout.HEADER_SUBJECT, Icons.of(Material.COMMAND_BLOCK, "<red>Economy",
                "<gray>Every change is written to the settings file at once."));
        set(MenuLayout.HEADER_RIGHT, Icons.of(Material.COMPARATOR, "<white>Every setting",
                "<gray>All of the economy's settings, by topic.", "<yellow>Click<gray> to open"),
                click -> settings(services, viewer, this, "economy"));

        band(MenuLayout.WHO, 2, Icons.of(Material.LEVER, "<white>Features",
                "<gray>" + FeatureSwitchMenu.SWITCHES.size() + " switches, one click each.",
                "<yellow>Click<gray> to open"), click -> new FeatureSwitchMenu(services, viewer, this).open());
        band(MenuLayout.WHO, 4, Icons.of(Material.GOLD_BLOCK, "<gold>Casino",
                "<gray>The house edge, bets and rules,", "<gray>for all games and for each.",
                "<gray>The house keeps " + CasinoMenu.percent(live.houseEdge()) + "%",
                "<yellow>Click<gray> to open"), click -> new GamesMenu(services, viewer, this).open());
        band(MenuLayout.WHO, 6, Icons.of(Material.EMERALD, "<green>Shop items",
                "<gray>Put any item in the shop and change",
                "<gray>what anything costs or pays.",
                "<yellow>Click<gray> to open"), click -> new ShopEditorMenu(services, viewer, this).open());

        band(MenuLayout.WHO, 8, Icons.of(Material.BEACON, "<aqua>Money supply",
                "<gray>A hard cap and the treasury, the price", "<gray>index, the stabiliser, and every brake",
                "<gray>against inflation.", "<yellow>Click<gray> to open"),
                click -> settings(services, viewer, this, "supply"));
        door(MenuLayout.RULES, 1, Material.CLOCK, "Interest", "economy/interest", live.interestEnabled(),
                "features.interest", "<gray>" + CasinoMenu.percent(live.interestRate()) + "% every "
                        + live.interestMinutes() + " minutes",
                "<gray>At most " + Mini.of(currency.render(live.interestCapMoney())) + " a payout");
        door(MenuLayout.RULES, 2, Material.GOLD_INGOT, "Loans", "economy/loans", live.loansEnabled(),
                "features.loans", "<gray>" + Mini.of(currency.render(live.loanLeastMoney())) + "<gray> to "
                        + Mini.of(currency.render(live.loanMostMoney())) + "<gray>, "
                        + CasinoMenu.percent(live.loanInterest()) + "% interest,",
                "<gray>due in " + live.loanDays() + " day(s), " + CasinoMenu.percent(live.loanLate()) + "% a day late.",
                "<dark_gray>/eco loan <player> [forgive]");
        door(MenuLayout.LAND, 2, Material.DIAMOND_PICKAXE, "Earning", "economy/earn", true, null,
                "<gray>Daily reward, passive income,", "<gray>advancements, the hourly cap.");
        door(MenuLayout.LAND, 3, Material.NAME_TAG, "Raffles and giveaways", "economy/raffles", live.rafflesEnabled(),
                "features.raffles", "<gray>Fees, lengths, how many at once.");
        door(MenuLayout.LAND, 4, Material.EXPERIENCE_BOTTLE, "Experience", "economy/experience", live.xpTradeEnabled(),
                "features.xp-trade", "<gray>What a point costs and pays.");
        door(MenuLayout.LAND, 5, Material.OAK_SIGN, "Sidebar and leaderboards", "economy/display", live.sidebarEnabled(),
                "features.sidebar", "<gray>The balance on screen, boards in the world.");
        door(MenuLayout.LAND, 6, Material.ENDER_CHEST, "Accounts", "economy/accounts", true, null,
                "<gray>Starting balance, the most an account holds,", "<gray>how long statements are kept.");
        door(MenuLayout.RULES, 3, Material.WRITABLE_BOOK, "Paying and bills", "economy/pay", live.payEnabled(),
                "features.pay", "<gray>Tax, minimum, hiring.");
        door(MenuLayout.RULES, 4, Material.PAPER, "Coins and notes", "economy/cash", live.cashEnabled(),
                "features.cash", "<gray>Fees, cheques, right click.");
        door(MenuLayout.RULES, 5, Material.CHEST, "Shop", "economy/shop", live.shopEnabled(), "features.shop",
                "<gray>Markup, sell prices, supply and demand,", "<gray>spawn eggs, drawers.");
        door(MenuLayout.RULES, 6, Material.BELL, "Auctions", "economy/auctions", live.auctionsEnabled(),
                "features.auctions", "<gray>Lengths, fees, the queue.");
        door(MenuLayout.RULES, 7, Material.IRON_BARS, "Wealth tax", "economy/tax", live.wealthTaxEnabled(),
                "features.wealth-tax", "<gray>" + CasinoMenu.percent(live.wealthTaxPercent() / 100) + "% every "
                        + live.wealthTaxHours() + " hours");

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

    /**
     * One topic of the settings: a click opens its page; a right click flips its switch, if it has one.
     *
     * @param toggle the feature switch's key, or null when the topic has none
     */
    private void door(int band, int column, Material icon, String name, String path, boolean on, String toggle,
                      String... lines) {
        List<String> lore = new ArrayList<>();
        if (toggle != null) {
            lore.add(on ? "<green>On" : "<red>Off");
        }
        lore.addAll(List.of(lines));
        lore.add("");
        lore.add("<yellow>Click<gray> for its settings");
        if (toggle != null) {
            lore.add("<yellow>Right click<gray> to switch it " + (on ? "off" : "on"));
        }
        band(band, column, Icons.of(on ? icon : Material.GRAY_DYE, (on ? "<white>" : "<gray>") + name, lore), click -> {
            if (toggle != null && click.isRightClick()) {
                services.store().cycle(toggle);
                services.store().trySave();
                refresh();
            } else {
                settings(services, viewer, this, path);
            }
        });
    }

    /** Core's settings window, opened at one of the economy's pages, with Back leading here. */
    static void settings(EconomyServices services, Player viewer, Menu parent, String path) {
        new SettingsMenu(viewer, services.brand(), services.core().chatFor(services.brand()),
                services.core().settingsNavigation(), path, parent).open();
    }

    @Override
    public String describe() {
        return "every switch of the economy, and the owner's tools";
    }
}
