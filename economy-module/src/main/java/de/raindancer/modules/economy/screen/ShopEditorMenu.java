package de.raindancer.modules.economy.screen;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.ui.choose.Catalogue;
import de.raindancer.core.ui.choose.ItemChooser;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.model.PriceTag;
import de.raindancer.modules.economy.model.PricedNames;
import de.raindancer.modules.economy.util.Mini;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * The owner's shop list: every item with a price of its own, and the way to put any other item in — an elytra,
 * a mace, anything the recipes cannot price. Each opens the price editor; a value or a buy price puts the item
 * in its drawer.
 */
public final class ShopEditorMenu extends PaginatedMenu<Material> implements IEconomyScreen {

    private final EconomyServices services;

    ShopEditorMenu(EconomyServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Shop items");
    }

    @Override
    public String breadcrumb() {
        return "Shop items";
    }

    @Override
    protected List<Material> entries() {
        EconomySettings live = services.config();
        Currency currency = services.currency();
        TreeSet<String> names = new TreeSet<>();
        for (List<String> lines : List.of(live.customValues(), live.buyPrices(), live.sellPrices())) {
            names.addAll(PricedNames.parse(lines, currency).amounts().keySet());
        }
        List<Material> items = new ArrayList<>();
        for (String name : names) {
            Material material = Material.matchMaterial(name);
            if (material != null && material.isItem()) {
                items.add(material);
            }
        }
        return items;
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.BARREL, "<gray>No item has a price of its own yet", "<gray>Add one below");
    }

    @Override
    protected ItemStack icon(Material material) {
        Currency currency = services.currency();
        PriceTag tag = services.shop().tag(material);
        return Icons.of(material, "<white>" + Catalogue.readable(material.name()),
                "<gray>Buys for: " + (tag.buyable() ? Mini.of(currency.render(tag.buy())) : "<red>not sold"),
                "<gray>Sells for: " + (tag.sellable() ? Mini.of(currency.render(tag.sell())) : "<red>not bought"),
                "<yellow>Click<gray> to change it");
    }

    @Override
    protected void onClick(Material material, InventoryClickEvent event) {
        new PriceEditMenu(services, viewer, this, material).open();
    }

    @Override
    protected void render() {
        super.render();
        toolbar(1, Icons.of(Material.CHEST, "<green>Add any item",
                "<gray>Pick from every item on the server."), click ->
                new ItemChooser(viewer, services.brand(), this, "Put in the shop…",
                        material -> new PriceEditMenu(services, viewer, this, material).open(),
                        ItemChooser.everythingOnThisServer()).open());
        toolbar(3, Icons.of(Material.ITEM_FRAME, "<green>Add the item in your hand",
                "<gray>Hold it, then click."), click -> {
            Material held = viewer.getInventory().getItemInMainHand().getType();
            if (held.isAir()) {
                services.messages().send(viewer, "economy.admin.shop-hold");
                return;
            }
            new PriceEditMenu(services, viewer, this, held).open();
        });
    }

    @Override
    protected List<String> helpLines() {
        return List.of("Items with a price of their own.", "Add any item, give it a value or a buy price,",
                "and it is in its shop drawer.");
    }

    @Override
    public String describe() {
        return "the owner's shop items";
    }
}
