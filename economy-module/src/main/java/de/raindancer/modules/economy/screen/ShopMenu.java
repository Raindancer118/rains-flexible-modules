package de.raindancer.modules.economy.screen;

import de.raindancer.core.ui.choose.Category;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.ui.prompt.AnvilInput;
import de.raindancer.core.ui.prompt.Parsers;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.util.Mini;
import de.raindancer.modules.economy.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * {@code /shop}: the creative inventory's drawers. A drawer the owner closed is shown greyed with the
 * reason rather than hidden, so the page is the same shape for everybody. Admins switch a drawer with a
 * right click.
 */
public final class ShopMenu extends Menu implements IEconomyScreen {

    private final EconomyServices services;

    public ShopMenu(EconomyServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Shop");
    }

    @Override
    public String breadcrumb() {
        return "Shop";
    }

    @Override
    protected void render() {
        EconomySettings live = services.config();
        boolean admin = viewer.hasPermission(PermissionNodes.ADMIN);
        set(MenuLayout.HEADER_SUBJECT, Icons.of(Material.EMERALD, "<green>Shop",
                "<gray>Your balance: " + Mini.of(services.currency().render(services.economy().balance(viewer.getUniqueId()))),
                "<dark_gray>Prices move with supply and demand."));

        Category[] categories = Category.values();
        for (int i = 0; i < categories.length; i++) {
            Category category = categories[i];
            int band = i < 5 ? MenuLayout.WHO : MenuLayout.RULES;
            int column = i < 5 ? 1 + i * 3 / 2 : 2 + (i - 5) * 3 / 2;
            boolean open = live.categoryOpen(category);
            int items = open ? services.shop().prices().tradableIn(category).size() : 0;
            Material icon = Material.matchMaterial(category.icon());
            List<String> lore = new java.util.ArrayList<>();
            lore.add(open ? "<gray>" + items + " item(s) for sale" : "<red>Closed on this server");
            if (admin) {
                lore.add("");
                lore.add("<yellow>Right click<gray> to " + (open ? "close" : "open") + " it");
            }
            band(band, column, Icons.of(icon == null ? Material.CHEST : icon,
                    (open ? "<green>" : "<gray>") + category.title(), lore), click -> {
                if (admin && click.isRightClick()) {
                    services.store().set(EconomySettings.categoryKey(category), String.valueOf(!open));
                    services.store().trySave();
                    refresh();
                } else if (open) {
                    services.screens().shopCategory(viewer, category);
                } else {
                    services.messages().send(viewer, "economy.shop.category-closed", "category", category.title());
                }
            });
        }

        band(MenuLayout.LAND, 3, Icons.of(Material.SPYGLASS, "<white>Search", "<gray>Find an item by name."),
                click -> AnvilInput.open(viewer, "Search the shop", "", Parsers.text(32),
                        text -> services.screens().shopSearch(viewer, text), this::open));
        band(MenuLayout.LAND, 5, live.sellingEnabled(), Icons.of(Material.CHEST, "<white>Sell",
                "<gray>What you carry that the shop buys."), BankMenu.OFF, click -> services.screens().sell(viewer));
        band(MenuLayout.LAND, 1, live.enchantBooks(), Icons.of(Material.ENCHANTED_BOOK, "<light_purple>Enchantments",
                "<gray>Any enchantment as a book, for an anvil.",
                "<gray>A level: " + Mini.of(services.currency().render(live.enchantPriceMoney()))), BankMenu.OFF,
                click -> new EnchantMenu(services, viewer, this).open());
        band(MenuLayout.LAND, 7, live.xpTradeEnabled(), Icons.of(Material.EXPERIENCE_BOTTLE, "<green>Experience",
                "<gray>Buy levels, priced by the point.",
                "<gray>A point: " + Mini.of(services.currency().render(live.xpBuyMoney()))), BankMenu.OFF,
                click -> new ExperienceMenu(services, viewer, this).open());
    }

    @Override
    protected List<String> helpLines() {
        return List.of("Sorted the way the creative inventory is.", "Click a drawer, then an item to buy or sell it.");
    }

    @Override
    public String describe() {
        return "the shop's drawers";
    }
}
