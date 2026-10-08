package de.raindancer.modules.economy.screen;

import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.model.Auction;
import de.raindancer.modules.economy.util.Mini;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The auctions waiting their turn, in order. A seller can take back their own from here. */
public final class AuctionQueueMenu extends PaginatedMenu<Auction> implements IEconomyScreen {

    private final EconomyServices services;

    AuctionQueueMenu(EconomyServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Auctions waiting");
    }

    @Override
    public String breadcrumb() {
        return "Queue";
    }

    @Override
    protected List<Auction> entries() {
        return services.auctions().auctions().stream().filter(auction -> !auction.live()).toList();
    }

    @Override
    protected ItemStack icon(Auction auction) {
        ItemStack item = services.auctions().item(auction);
        int place = entries().indexOf(auction) + 1;
        List<Component> lore = new ArrayList<>(Optional.ofNullable(item.lore()).orElse(List.of()));
        lore.add(Component.empty());
        lore.add(line("<gray>Number " + place + " in line, sold by <white>" + auction.sellerName()));
        lore.add(line("<gray>Starts at " + Mini.of(services.currency().render(auction.start()))
                + " <gray>· runs " + Times.describe(Duration.ofSeconds(auction.seconds()))));
        if (auction.hasBuyout()) {
            lore.add(line("<gray>Buy it now: " + Mini.of(services.currency().render(auction.buyout()))));
        }
        if (auction.seller().equals(viewer.getUniqueId())) {
            lore.add(line("<yellow>Click<gray> to take it back"));
        }
        item.lore(lore);
        return item;
    }

    @Override
    protected void onClick(Auction auction, InventoryClickEvent event) {
        if (!auction.seller().equals(viewer.getUniqueId())) {
            return;
        }
        new ConfirmScreen(viewer, services.brand(), this, "Take back " + auction.itemName() + "?",
                List.of("It comes back to your inventory."), () -> services.auctions().withdraw(viewer, auction.id()))
                .open();
    }

    private static Component line(String text) {
        return MiniMessage.miniMessage().deserialize(text).decoration(TextDecoration.ITALIC, false);
    }

    @Override
    protected List<String> helpLines() {
        return List.of("The auctions waiting their turn, in order.", "Your own can be taken back from here.");
    }

    @Override
    public String describe() {
        return "the auctions waiting their turn";
    }
}
