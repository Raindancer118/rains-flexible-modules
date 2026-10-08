package de.raindancer.modules.economy.screen;

import de.raindancer.core.ui.choose.Catalogue;
import de.raindancer.core.ui.choose.Category;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.model.PriceTag;
import de.raindancer.modules.economy.util.Mini;
import de.raindancer.modules.economy.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One drawer of the shop, or a search across all of them. Click an item to trade it. */
public final class ShopItemsMenu extends PaginatedMenu<Material> implements IEconomyScreen {

    private final EconomyServices services;
    private final String heading;
    private final List<Material> items;

    private ShopItemsMenu(EconomyServices services, Player viewer, Menu parent, String heading, List<Material> items) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.heading = heading;
        this.items = items;
    }

    public static ShopItemsMenu of(EconomyServices services, Player viewer, Menu parent, Category category) {
        return new ShopItemsMenu(services, viewer, parent, category.title(),
                materials(services.shop().prices().tradableIn(category)));
    }

    public static ShopItemsMenu eggs(EconomyServices services, Player viewer, Menu parent) {
        return new ShopItemsMenu(services, viewer, parent, "Spawn eggs", materials(services.shop().prices().tradableEggs()));
    }

    public static ShopItemsMenu search(EconomyServices services, Player viewer, Menu parent, String text) {
        String wanted = text.strip().toUpperCase(Locale.ROOT).replace(' ', '_');
        List<String> found = new ArrayList<>();
        for (Category category : Category.values()) {
            for (String name : services.shop().prices().tradableIn(category)) {
                if (name.contains(wanted)) {
                    found.add(name);
                }
            }
        }
        for (String name : services.shop().prices().tradableEggs()) {
            if (name.contains(wanted)) {
                found.add(name);
            }
        }
        found.sort((a, b) -> a.equals(wanted) ? -1 : b.equals(wanted) ? 1 : a.compareTo(b));
        return new ShopItemsMenu(services, viewer, parent, "\"" + text.strip() + "\"", materials(found));
    }

    private static List<Material> materials(List<String> names) {
        List<Material> read = new ArrayList<>();
        for (String name : names) {
            Material material = Material.matchMaterial(name);
            if (material != null && material.isItem()) {
                read.add(material);
            }
        }
        return read;
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>" + MiniMessage.miniMessage().escapeTags(heading));
    }

    @Override
    public String breadcrumb() {
        return heading;
    }

    @Override
    protected List<Material> entries() {
        return items;
    }

    @Override
    protected ItemStack icon(Material material) {
        PriceTag tag = services.shop().tag(material);
        List<String> lore = new ArrayList<>();
        lore.add(tag.buyable() ? "<gray>Buy: " + Mini.of(services.currency().render(tag.buy())) : "<dark_gray>Not sold");
        lore.add(tag.sellable() ? "<gray>Sell: " + Mini.of(services.currency().render(tag.sell())) : "<dark_gray>Not bought");
        lore.add("");
        lore.add("<yellow>Click<gray> to trade");
        if (viewer.hasPermission(PermissionNodes.ADMIN)) {
            lore.add("<yellow>Right click<gray> to change its prices");
        }
        return Icons.of(material, "<white>" + Catalogue.readable(material.name()), lore);
    }

    @Override
    protected void onClick(Material material, InventoryClickEvent event) {
        if (event.isRightClick() && viewer.hasPermission(PermissionNodes.ADMIN)) {
            new PriceEditMenu(services, viewer, this, material).open();
        } else {
            new TradeMenu(services, viewer, this, material).open();
        }
    }

    @Override
    public String describe() {
        return "one drawer of the shop";
    }
}
