package de.raindancer.modules.economy.screen;

import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.model.SaleLot;
import de.raindancer.modules.economy.util.Mini;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything you carry that the shop buys, with what it fetches: plain items by kind, enchanted and worn
 * ones one by one, shown as themselves. Click sells.
 */
public final class SellMenu extends PaginatedMenu<SaleLot> implements IEconomyScreen {

    private final EconomyServices services;
    private List<SaleLot> lots = List.of();

    public SellMenu(EconomyServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Sell");
    }

    @Override
    public String breadcrumb() {
        return "Sell";
    }

    @Override
    protected List<SaleLot> entries() {
        lots = services.shop().lots(viewer);
        return lots;
    }

    @Override
    protected void render() {
        super.render();
        Money everything = Money.ZERO;
        for (SaleLot lot : lots) {
            everything = everything.plus(lot.total());
        }
        Money total = everything;
        toolbar(4, !lots.isEmpty(), Icons.of(Material.HOPPER, "<green>Sell everything",
                "<gray>All of it, for " + Mini.of(services.currency().render(total))),
                "You carry nothing the shop buys.", click -> new ConfirmScreen(viewer, services.brand(), this,
                        "<yellow>Sell everything?", List.of("<gray>Every item listed here goes, for "
                        + Mini.of(services.currency().render(total)) + "."), () -> {
                    services.shop().sellEverything(viewer);
                    open();
                }).open());
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>Nothing to sell",
                "<dark_gray>Items with lore or plugin data are not bought.");
    }

    @Override
    protected ItemStack icon(SaleLot lot) {
        String fetches = "<gray>Fetches " + Mini.of(services.currency().render(lot.total()));
        if (lot.plain()) {
            return Icons.of(lot.material(), "<white>" + lot.count() + " × " + lot.label(), fetches, "",
                    "<yellow>Click<gray> to sell them all");
        }
        ItemStack shown = viewer.getInventory().getItem(lot.slot());
        if (shown == null) {
            return Icons.of(lot.material(), "<white>" + lot.label(), fetches);
        }
        shown = shown.clone();
        ItemMeta meta = shown.getItemMeta();
        List<Component> lore = new ArrayList<>(meta.hasLore() && meta.lore() != null ? meta.lore() : List.of());
        lore.add(Component.empty());
        lore.add(Icons.loreLine(fetches));
        lore.add(Icons.loreLine("<yellow>Click<gray> to sell it"));
        meta.lore(lore);
        shown.setItemMeta(meta);
        return shown;
    }

    @Override
    protected void onClick(SaleLot lot, InventoryClickEvent event) {
        services.shop().sellLot(viewer, lot);
        refresh();
    }

    @Override
    public String describe() {
        return "what you carry that the shop buys";
    }
}
