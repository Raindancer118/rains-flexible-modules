package de.raindancer.modules.economy.screen;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.choose.Category;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.ui.prompt.AnvilInput;
import de.raindancer.core.ui.prompt.Parsers;
import de.raindancer.modules.economy.service.ClientShop;
import de.raindancer.modules.economy.service.ShopDesk;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/**
 * {@code /shop} on a server that is not the bank: the bank's drawers, priced by the bank. What only the bank's own
 * shop has — books, potions, packs, experience — is shown greyed with where it is, so the page is the one players know.
 */
public final class ClientShopMenu extends Menu implements IEconomyScreen {

    private final ClientShop shop;
    private final Brand brand;
    private final List<ShopDesk.Line> lines;

    public ClientShopMenu(ClientShop shop, Brand brand, Player viewer, Menu parent, List<ShopDesk.Line> lines) {
        super(viewer, brand, parent);
        this.shop = shop;
        this.brand = brand;
        this.lines = List.copyOf(lines);
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
        set(MenuLayout.HEADER_SUBJECT, Icons.of(Material.EMERALD, "<green>Shop",
                "<gray>The network's shop, with your money", "<gray>from " + MiniMessage.miniMessage()
                        .escapeTags(shop.bankName()) + ".", "<dark_gray>Prices move with supply and demand."));
        Category[] categories = Category.values();
        for (int i = 0; i < categories.length; i++) {
            Category category = categories[i];
            int band = i < 5 ? MenuLayout.WHO : MenuLayout.RULES;
            int column = i < 5 ? 1 + i * 3 / 2 : 2 + (i - 5) * 3 / 2;
            List<ShopDesk.Line> in = drawer(category.name().toLowerCase(Locale.ROOT));
            Material icon = Material.matchMaterial(category.icon());
            band(band, column, !in.isEmpty(), Icons.of(icon == null ? Material.CHEST : icon,
                            "<green>" + category.title(), "<gray>" + in.size() + " item(s) for sale"),
                    "Closed on the bank's server.",
                    click -> new ClientItemsMenu(shop, brand, viewer, this, category.title(), in).open());
        }
        List<ShopDesk.Line> eggs = drawer("eggs");
        band(MenuLayout.RULES, 4, !eggs.isEmpty(), Icons.of(Material.PIG_SPAWN_EGG, "<green>Spawn eggs",
                        "<gray>" + eggs.size() + " mob(s) to hatch."), "Closed on the bank's server.",
                click -> new ClientItemsMenu(shop, brand, viewer, this, "Spawn eggs", eggs).open());
        band(MenuLayout.LAND, 3, Icons.of(Material.SPYGLASS, "<white>Search", "<gray>Find an item by name."),
                click -> AnvilInput.open(viewer, "Search the shop", "", Parsers.text(32), text -> {
                    var rule = new de.raindancer.modules.economy.rules.ShopSearchRule();
                    new ClientItemsMenu(shop, brand, viewer, this, "\"" + text.strip() + "\"", lines.stream()
                            .filter(line -> rule.score(text, de.raindancer.core.ui.choose.Catalogue.readable(line.material()))
                                    .isPresent())
                            .sorted(java.util.Comparator.comparingInt(line -> rule.score(text,
                                    de.raindancer.core.ui.choose.Catalogue.readable(line.material())).orElse(9)))
                            .toList()).open();
                }, this::open));
        band(MenuLayout.LAND, 5, Icons.of(Material.CHEST, "<white>Sell",
                "<gray>Everything plain you carry", "<gray>that the shop buys.", "", "<yellow>Click to sell it all."),
                click -> {
                    viewer.closeInventory();
                    shop.sellAll(viewer);
                });
        String elsewhere = "Only on " + shop.bankName() + ".";
        band(MenuLayout.LAND, 1, false, Icons.of(Material.ENCHANTED_BOOK, "<light_purple>Enchantments"), elsewhere,
                click -> { });
        band(MenuLayout.LAND, 2, false, Icons.of(Material.POTION, "<aqua>Potions"), elsewhere, click -> { });
        band(MenuLayout.LAND, 4, false, Icons.of(Material.BUNDLE, "<gold>Packs"), elsewhere, click -> { });
        band(MenuLayout.LAND, 7, false, Icons.of(Material.EXPERIENCE_BOTTLE, "<green>Experience"), elsewhere,
                click -> { });
    }

    private List<ShopDesk.Line> drawer(String drawer) {
        return lines.stream().filter(line -> line.drawer().equals(drawer)).toList();
    }

    @Override
    protected List<String> helpLines() {
        return List.of("Sorted the way the creative inventory is.", "Click a drawer, then an item to buy or sell it.");
    }

    @Override
    public String describe() {
        return "the bank's shop on another server";
    }
}
