package de.raindancer.modules.economy.screen;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.choose.Catalogue;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.model.PriceTag;
import de.raindancer.modules.economy.model.YourPrice;
import de.raindancer.modules.economy.model.Bulk;
import de.raindancer.modules.economy.util.Mini;
import de.raindancer.modules.economy.util.PriceLines;
import de.raindancer.modules.economy.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** One item: buy one to a stack, sell one to everything you carry. Prices are re-read on every click. */
public final class TradeMenu extends Menu implements IEconomyScreen {

    private static final int[] AMOUNTS = {1, 8, 16, 32, 64};
    /** A full inventory of stacks of 64. */
    private static final int MOST_AT_ONCE = 2304;

    private final EconomyServices services;
    private final Material material;

    public TradeMenu(EconomyServices services, Player viewer, Menu parent, Material material) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.material = material;
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>" + Catalogue.readable(material.name()));
    }

    @Override
    public String breadcrumb() {
        return Catalogue.readable(material.name());
    }

    @Override
    protected void render() {
        Currency currency = services.currency();
        YourPrice yours = services.shop().priceFor(viewer.getUniqueId(), material);
        PriceTag tag = yours.shop();
        int carrying = services.shop().carrying(viewer, material);
        set(MenuLayout.HEADER_LEFT, Icons.of(Material.GOLD_INGOT, "<white>Your balance",
                Mini.of(currency.render(services.economy().balance(viewer.getUniqueId())))));
        set(MenuLayout.HEADER_SUBJECT, Icons.of(material, "<white>" + Catalogue.readable(material.name()),
                header(currency, yours)));
        set(MenuLayout.HEADER_RIGHT, Icons.of(Material.CHEST, "<white>You carry " + carrying));

        int stack = Math.max(1, material.getMaxStackSize());
        int column = 1;
        for (int amount : AMOUNTS) {
            if (amount > stack && amount != 1) {
                continue;
            }
            int count = amount;
            band(MenuLayout.WHO, column, tag.buyable() && viewer.hasPermission(PermissionNodes.SHOP),
                    Icons.of(Material.LIME_STAINED_GLASS_PANE, "<green>Buy " + count,
                            "<gray>For " + PriceLines.amount(currency, tag.buy().times(count),
                                    yours.buyFor(count).orElse(Money.ZERO))),
                    "The shop does not sell this.", click -> {
                        services.shop().buy(viewer, material, count);
                        refresh();
                    });
            band(MenuLayout.LAND, column, tag.sellable() && carrying >= count && viewer.hasPermission(PermissionNodes.SELL),
                    Icons.of(Material.RED_STAINED_GLASS_PANE, "<red>Sell " + count,
                            "<gray>For " + PriceLines.amount(currency, tag.sell().times(count),
                                    yours.sellFor(count).orElse(Money.ZERO))),
                    tag.sellable() ? "You do not carry that many." : "The shop does not buy this.", click -> {
                        services.shop().sell(viewer, material, count);
                        refresh();
                    });
            column += 1;
        }
        band(MenuLayout.LAND, 7, tag.sellable() && carrying > 0 && viewer.hasPermission(PermissionNodes.SELL),
                Icons.of(Material.HOPPER, "<red>Sell all " + carrying,
                        "<gray>For " + PriceLines.amount(currency,
                                tag.sell().times(Math.max(0, carrying)),
                                yours.sellFor(Math.max(0, carrying)).orElse(Money.ZERO))),
                tag.sellable() ? "You carry none." : "The shop does not buy this.", click -> {
                    services.shop().sell(viewer, material, carrying);
                    refresh();
                });
        // The bulk steps get buttons of their own: nobody finds a discount that only a typed number reaches.
        int[] bulkColumns = {1, 2, 6, 7};
        int placed = 0;
        for (Bulk.Tier tier : yours.bulk().tiers()) {
            if (placed >= bulkColumns.length || tier.from() > MOST_AT_ONCE) {
                break;
            }
            int count = tier.from();
            int percent = yours.bulk().percentFor(count);
            band(MenuLayout.RULES, bulkColumns[placed++], tag.buyable() && viewer.hasPermission(PermissionNodes.SHOP),
                    Icons.of(Material.CHEST, "<green>Buy " + count + " <dark_gray>(" + stacks(count, stack) + ")",
                            "<gray>For " + PriceLines.amount(currency, tag.buy().times(count),
                                    yours.buyFor(count).orElse(Money.ZERO)),
                            "<gold>Bulk: " + percent + "% off",
                            "<dark_gray>Needs room for all of it."),
                    "The shop does not sell this.", click -> {
                        services.shop().buy(viewer, material, count);
                        refresh();
                    });
        }
        band(MenuLayout.RULES, 4, Icons.of(Material.ANVIL, "<white>Buy a number you type",
                yours.bulk().applies() ? "<gray>Bulk discounts count here too." : ""), click ->
                de.raindancer.core.ui.prompt.AnvilInput.open(viewer, "Buy how many?", "",
                        de.raindancer.core.ui.prompt.Parsers.wholeNumber(1, MOST_AT_ONCE), count -> {
                            services.shop().buy(viewer, material, count);
                            open();
                        }, this::open));
        if (viewer.hasPermission(PermissionNodes.ADMIN)) {
            toolbar(4, Icons.of(Material.COMMAND_BLOCK, "<red>Change its prices"),
                    click -> new PriceEditMenu(services, viewer, this, material).open());
        }
    }

    @Override
    protected List<String> helpLines() {
        return List.of("Green buys, red sells.", "Buying a lot makes it dearer for a while;",
                "selling a lot makes it cheaper.");
    }

    @Override
    public String describe() {
        return "buying and selling one item";
    }

    private static String stacks(int count, int stack) {
        int whole = count / Math.max(1, stack);
        return whole + (whole == 1 ? " stack" : " stacks") + (count % Math.max(1, stack) == 0 ? "" : " and some");
    }

    private List<String> header(Currency currency, YourPrice yours) {
        PriceTag tag = yours.shop();
        List<String> lines = new java.util.ArrayList<>();
        lines.add(tag.buyable() ? "<gray>Buy one: " + PriceLines.amount(currency, tag.buy(), yours.buy())
                : services.shop().saleStopped(material).map(why -> "<gold>Not sold now: "
                + net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(why)).orElse("<dark_gray>Not sold"));
        lines.add(tag.sellable() ? "<gray>Sell one: " + PriceLines.amount(currency, tag.sell(), yours.sell())
                : "<dark_gray>Not bought");
        String trend = ShopItemsMenu.trend(services.shop().prices().multiplier(material.name()));
        if (!trend.isEmpty()) {
            lines.add(trend);
        }
        lines.addAll(PriceLines.why(yours));
        String bulk = PriceLines.bulk(yours.bulk());
        if (!bulk.isEmpty()) {
            lines.add(bulk);
        }
        lines.add("<dark_gray>Priced from: " + tag.source().name().toLowerCase());
        return lines;
    }
}
