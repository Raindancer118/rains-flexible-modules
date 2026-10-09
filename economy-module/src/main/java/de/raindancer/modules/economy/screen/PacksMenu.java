package de.raindancer.modules.economy.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.model.Pack;
import de.raindancer.modules.economy.model.PackPrice;
import de.raindancer.modules.economy.util.Mini;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The shop's Packs drawer: each pack as the item it is, with this player's price. Click to buy one. */
public final class PacksMenu extends PaginatedMenu<Pack> implements IEconomyScreen {

    private final EconomyServices services;

    public PacksMenu(EconomyServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Packs");
    }

    @Override
    public String breadcrumb() {
        return "Packs";
    }

    @Override
    protected List<Pack> entries() {
        return services.packs().packs();
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.BUNDLE, "<gray>No packs for sale", "<dark_gray>Written in packs.yml");
    }

    @Override
    protected ItemStack icon(Pack pack) {
        ItemStack item = services.packs().item(pack);
        List<Component> lore = new ArrayList<>(Optional.ofNullable(item.lore()).orElse(List.of()));
        // The item's own last line is about unpacking; in the shop the click buys.
        if (!lore.isEmpty()) {
            lore.removeLast();
        }
        Optional<PackPrice> price = services.packs().price(viewer.getUniqueId(), pack);
        if (price.isEmpty()) {
            item.lore(lore);
            return Icons.locked(item, "Something in it has no price in the shop.");
        }
        PackPrice cost = price.get();
        lore.add(Icons.loreLine("<gray>Price: " + Mini.of(services.currency().render(cost.price()))));
        if (cost.saves()) {
            lore.add(Icons.loreLine("<dark_gray>One by one: <st>" + Mini.of(services.currency().render(cost.contents()))
                    + "</st>"));
        }
        if (pack.once()) {
            lore.add(Icons.loreLine("<gold>One each"));
        }
        if (services.packs().hadIt(viewer, pack)) {
            item.lore(lore);
            return Icons.locked(item, "You have had this one. It is one each.");
        }
        lore.add(Component.empty());
        lore.add(Icons.loreLine("<yellow>Click<gray> to buy one"));
        item.lore(lore);
        return item;
    }

    @Override
    protected void onClick(Pack pack, InventoryClickEvent event) {
        services.packs().buy(viewer, pack);
        refresh();
    }

    @Override
    protected List<String> helpLines() {
        return List.of("Ready-made bundles, cheaper than buying it all one by one.",
                "A pack is an item: right click it to unpack, or give it away.");
    }

    @Override
    public String describe() {
        return "packs for sale";
    }
}
