package de.raindancer.modules.economy.screen;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.choose.Catalogue;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.economy.service.ClientShop;
import de.raindancer.modules.economy.service.ShopDesk;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** One drawer of the bank's shop, or a search across it, with the bank's prices. Click an item to trade it. */
public final class ClientItemsMenu extends PaginatedMenu<ShopDesk.Line> implements IEconomyScreen {

    private final ClientShop shop;
    private final Brand brand;
    private final String heading;
    private final List<ShopDesk.Line> lines;

    public ClientItemsMenu(ClientShop shop, Brand brand, Player viewer, Menu parent, String heading,
                           List<ShopDesk.Line> lines) {
        super(viewer, brand, parent);
        this.shop = shop;
        this.brand = brand;
        this.heading = heading;
        this.lines = lines.stream().filter(line -> material(line) != null).toList();
    }

    private static Material material(ShopDesk.Line line) {
        Material material = Material.matchMaterial(line.material());
        return material != null && material.isItem() ? material : null;
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
    protected List<ShopDesk.Line> entries() {
        return lines;
    }

    @Override
    protected ItemStack icon(ShopDesk.Line line) {
        List<String> lore = new ArrayList<>();
        lore.add(line.buyable() ? "<gray>Buy: " + line.buy() : "<dark_gray>Not sold");
        lore.add(line.sellable() ? "<gray>Sell: " + line.sell() : "<dark_gray>Not bought");
        if (line.trend() > 0) {
            lore.add("<red>▲ " + line.trend() + "%<gray> — bought a lot lately");
        } else if (line.trend() < 0) {
            lore.add("<green>▼ " + -line.trend() + "%<gray> — sold a lot lately");
        }
        lore.add("");
        lore.add("<yellow>Click<gray> to trade");
        return Icons.of(material(line), "<white>" + Catalogue.readable(line.material()), lore);
    }

    @Override
    protected void onClick(ShopDesk.Line line, InventoryClickEvent event) {
        ClientTradeMenu.open(shop, brand, viewer, this, material(line));
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.BARRIER, "<gray>Nothing here", "<dark_gray>Try another drawer or a search.");
    }

    @Override
    public String describe() {
        return "one drawer of the bank's shop on another server";
    }
}
