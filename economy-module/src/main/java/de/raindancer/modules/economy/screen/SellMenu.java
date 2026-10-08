package de.raindancer.modules.economy.screen;

import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.choose.Catalogue;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.util.Mini;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Everything you carry that the shop buys, with what it fetches. Click sells all of one kind. */
public final class SellMenu extends PaginatedMenu<Material> implements IEconomyScreen {

    private final EconomyServices services;
    private Map<Material, Money> worth = Map.of();

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
    protected List<Material> entries() {
        worth = services.shop().valueOfInventory(viewer);
        return new ArrayList<>(worth.keySet());
    }

    @Override
    protected void render() {
        super.render();
        Money total = Money.ZERO;
        for (Money each : worth.values()) {
            total = total.plus(each);
        }
        Money everything = total;
        toolbar(4, !worth.isEmpty(), Icons.of(Material.HOPPER, "<green>Sell everything",
                "<gray>All of it, for " + Mini.of(services.currency().render(everything))),
                "You carry nothing the shop buys.", click -> new ConfirmScreen(viewer, services.brand(), this,
                        "<yellow>Sell everything?", List.of("<gray>Every item listed here goes, for "
                        + Mini.of(services.currency().render(everything)) + "."), () -> {
                    services.shop().sellEverything(viewer);
                    open();
                }).open());
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>Nothing to sell",
                "<dark_gray>Plain items only: no names, no enchantments, no wear.");
    }

    @Override
    protected ItemStack icon(Material material) {
        int count = services.shop().carrying(viewer, material);
        return Icons.of(material, "<white>" + count + " × " + Catalogue.readable(material.name()),
                "<gray>Fetches " + Mini.of(services.currency().render(worth.getOrDefault(material, Money.ZERO))),
                "", "<yellow>Click<gray> to sell them all");
    }

    @Override
    protected void onClick(Material material, InventoryClickEvent event) {
        services.shop().sell(viewer, material, services.shop().carrying(viewer, material));
        refresh();
    }

    @Override
    public String describe() {
        return "what you carry that the shop buys";
    }
}
