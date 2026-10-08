package de.raindancer.modules.economy.screen;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.ui.choose.Catalogue;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.model.PriceTag;
import de.raindancer.modules.economy.util.Mini;
import de.raindancer.modules.economy.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** One item: buy one to a stack, sell one to everything you carry. Prices are re-read on every click. */
public final class TradeMenu extends Menu implements IEconomyScreen {

    private static final int[] AMOUNTS = {1, 8, 16, 32, 64};

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
        PriceTag tag = services.shop().tag(material);
        int carrying = services.shop().carrying(viewer, material);
        set(MenuLayout.HEADER_LEFT, Icons.of(Material.GOLD_INGOT, "<white>Your balance",
                Mini.of(currency.render(services.economy().balance(viewer.getUniqueId())))));
        set(MenuLayout.HEADER_SUBJECT, Icons.of(material, "<white>" + Catalogue.readable(material.name()),
                tag.buyable() ? "<gray>Buy one: " + Mini.of(currency.render(tag.buy())) : "<dark_gray>Not sold",
                tag.sellable() ? "<gray>Sell one: " + Mini.of(currency.render(tag.sell())) : "<dark_gray>Not bought",
                ShopItemsMenu.trend(services.shop().prices().multiplier(material.name())),
                "<dark_gray>Priced from: " + tag.source().name().toLowerCase()));
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
                            "<gray>For " + Mini.of(currency.render(tag.buy().times(count)))),
                    "The shop does not sell this.", click -> {
                        services.shop().buy(viewer, material, count);
                        refresh();
                    });
            band(MenuLayout.LAND, column, tag.sellable() && carrying >= count && viewer.hasPermission(PermissionNodes.SELL),
                    Icons.of(Material.RED_STAINED_GLASS_PANE, "<red>Sell " + count,
                            "<gray>For " + Mini.of(currency.render(tag.sell().times(count)))),
                    tag.sellable() ? "You do not carry that many." : "The shop does not buy this.", click -> {
                        services.shop().sell(viewer, material, count);
                        refresh();
                    });
            column += 1;
        }
        band(MenuLayout.LAND, 7, tag.sellable() && carrying > 0 && viewer.hasPermission(PermissionNodes.SELL),
                Icons.of(Material.HOPPER, "<red>Sell all " + carrying,
                        "<gray>For " + Mini.of(currency.render(tag.sell().times(Math.max(0, carrying))))),
                tag.sellable() ? "You carry none." : "The shop does not buy this.", click -> {
                    services.shop().sell(viewer, material, carrying);
                    refresh();
                });
        band(MenuLayout.RULES, 4, Icons.of(Material.ANVIL, "<white>Buy a number you type"), click ->
                de.raindancer.core.ui.prompt.AnvilInput.open(viewer, "Buy how many?", "",
                        de.raindancer.core.ui.prompt.Parsers.wholeNumber(1, 2304), count -> {
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
}
