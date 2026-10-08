package de.raindancer.modules.economy.screen;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuAnimation;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.model.Auction;
import de.raindancer.modules.economy.service.AuctionService;
import de.raindancer.modules.economy.util.Mini;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The auction house: the item up right now with its bid and clock, ticking live, buttons to bid; a click on
 * anything in your own inventory puts it up; the queue and what you won.
 */
public final class AuctionMenu extends Menu implements IEconomyScreen {

    private final EconomyServices services;
    private final Menu back;

    AuctionMenu(EconomyServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.back = parent;
    }

    public static void open(EconomyServices services, Player viewer, Menu parent) {
        AuctionMenu menu = new AuctionMenu(services, viewer, parent);
        menu.open();
        MenuAnimation.loop(services.plugin(), menu, 10L, menu::refresh, () -> { });
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Auction house");
    }

    @Override
    public String breadcrumb() {
        return "Auctions";
    }

    @Override
    protected void render() {
        Currency currency = services.currency();
        AuctionService auctions = services.auctions();
        List<Auction> all = auctions.auctions();
        Optional<Auction> running = all.stream().filter(Auction::live).findFirst();
        long waiting = all.stream().filter(auction -> !auction.live()).count();

        set(MenuLayout.HEADER_LEFT, Icons.of(Material.GOLD_INGOT, "<white>Your balance",
                Mini.of(currency.render(services.economy().balance(viewer.getUniqueId())))));
        set(MenuLayout.HEADER_RIGHT, Icons.of(Material.HOPPER, "<white>Waiting: " + waiting,
                "<gray>Auctions in the queue", "<yellow>Click<gray> to see them"),
                click -> new AuctionQueueMenu(services, viewer, this).open());

        if (running.isEmpty()) {
            set(MenuLayout.HEADER_SUBJECT, Icons.of(Material.BELL, "<gold>Auction house"));
            set(2 * 9 + 4, Icons.of(Material.BARRIER, "<gray>No auction running",
                    waiting > 0 ? "<gray>The next one starts in a moment"
                            : "<gray>Click an item in your inventory to put it up"));
        } else {
            showLive(running.get(), currency, auctions);
        }

        band(MenuLayout.LAND, 1, Icons.of(Material.CHEST, "<green>Put something up",
                "<gray>Click any item in your inventory", "<gray>below to auction it.",
                "<dark_gray>or /auction sell <start> [buy it now] [5m]"), click -> {
            int held = viewer.getInventory().getHeldItemSlot();
            ItemStack hand = viewer.getInventory().getItem(held);
            if (hand == null || hand.getType().isAir()) {
                services.messages().send(viewer, "economy.auction.pick-an-item");
                return;
            }
            new AuctionSellMenu(services, viewer, this, held, hand).open();
        });
        int owed = auctions.claimsOf(viewer.getUniqueId()).size();
        band(MenuLayout.LAND, 3, Icons.of(owed > 0 ? Material.ENDER_CHEST : Material.CHEST_MINECART,
                (owed > 0 ? "<yellow>" : "<gray>") + "Waiting for you: " + owed,
                owed > 0 ? "<yellow>Click<gray> to put it in your inventory" : "<gray>Won and returned items land here"),
                click -> {
                    auctions.deliver(viewer);
                    refresh();
                });
        boolean listening = AuctionService.NEWS.isOn(viewer);
        band(MenuLayout.LAND, 5, Icons.of(listening ? Material.BELL : Material.GRAY_DYE,
                listening ? "<green>Announcements: on" : "<gray>Announcements: off",
                "<gray>Auctions in chat and on the boss bar", "<yellow>Click<gray> to switch"), click -> {
            auctions.toggleNews(viewer);
            refresh();
        });
        band(MenuLayout.LAND, 7, Icons.of(Material.NAME_TAG, "<light_purple>Raffles",
                "<gray>" + services.raffles().raffles().size() + " running", "<yellow>Click<gray> to see them"),
                click -> RaffleMenu.open(services, viewer, this));
    }

    /** An item clicked in the player's own inventory is the one they want to auction; nothing moves yet. */
    @Override
    public boolean allowBottomInventoryInteraction() {
        return true;
    }

    @Override
    public void handleBottomClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) {
            return;
        }
        new AuctionSellMenu(services, viewer, this, event.getSlot(), clicked).open();
    }

    private void showLive(Auction auction, Currency currency, AuctionService auctions) {
        long left = auctions.secondsLeft(auction);
        String time = Times.describe(Duration.ofSeconds(left));
        set(MenuLayout.HEADER_SUBJECT, Icons.of(left <= 10 ? Material.REDSTONE_TORCH : Material.CLOCK,
                (left <= 10 ? "<red><bold>" : "<yellow>") + time + " left",
                "<gray>A late bid gives everybody time again"));

        ItemStack item = auctions.item(auction);
        List<Component> lore = new ArrayList<>(Optional.ofNullable(item.lore()).orElse(List.of()));
        lore.add(Component.empty());
        lore.add(line("<gray>Sold by <white>" + auction.sellerName()));
        lore.add(line(auction.hasBid()
                ? "<gray>Highest bid: " + Mini.of(currency.render(auction.bid())) + " <gray>by <white>" + auction.bidderName()
                : "<gray>Starts at " + Mini.of(currency.render(auction.start()))));
        if (auction.hasBuyout()) {
            lore.add(line("<gray>Buy it now: " + Mini.of(currency.render(auction.buyout()))));
        }
        lore.add(line("<gray>" + auction.bids() + " bid(s) · " + time + " left"));
        item.lore(lore);
        set(2 * 9 + 4, item);

        set(2 * 9 + 2, Icons.head(auction.seller(), "<white>" + auction.sellerName(), "<gray>Selling"));
        if (auction.hasBid()) {
            boolean you = viewer.getUniqueId().equals(auction.bidder());
            set(2 * 9 + 6, Icons.head(auction.bidder(), "<white>" + auction.bidderName() + (you ? " <green>(you)" : ""),
                    "<gray>Highest bid: " + Mini.of(currency.render(auction.bid()))));
        } else {
            set(2 * 9 + 6, Icons.of(Material.SKELETON_SKULL, "<gray>No bids yet"));
        }

        boolean own = viewer.getUniqueId().equals(auction.seller());
        boolean top = viewer.getUniqueId().equals(auction.bidder());
        Money next = auctions.nextMinimum(auction);
        String why = own ? "This is your own auction." : top ? "You are the highest bidder." : "";
        toolbar(2, !own && !top, Icons.of(Material.LIME_CONCRETE, "<green>Bid " + Mini.of(currency.render(next)),
                "<gray>The smallest bid accepted now"), why, click -> {
            auctions.bid(viewer, next, auction.id().toString());
            refresh();
        });
        toolbar(4, !own && !top, Icons.of(Material.NAME_TAG, "<yellow>Type a bid",
                "<gray>At least " + Mini.of(currency.render(next))), why, click -> MoneyPrompt.ask(viewer, "Bid how much?",
                currency, amount -> {
                    auctions.bid(viewer, amount, auction.id().toString());
                    open(services, viewer, back);
                }, () -> open(services, viewer, back)));
        if (auction.hasBuyout()) {
            toolbar(6, !own, Icons.of(Material.GOLD_BLOCK, "<gold>Buy it now",
                    "<gray>" + Mini.of(currency.render(auction.buyout())) + " <gray>ends it at once"), why, click ->
                    new ConfirmScreen(viewer, services.brand(), this, "Buy it now?",
                            List.of("You pay " + currency.format(auction.buyout()) + " and the auction ends at once."),
                            () -> auctions.bid(viewer, auction.buyout(), auction.id().toString())).open());
        }
    }

    private static Component line(String text) {
        return MiniMessage.miniMessage().deserialize(text).decoration(TextDecoration.ITALIC, false);
    }

    @Override
    protected List<String> helpLines() {
        return List.of("One auction at a time for the whole server.", "A bid holds your money until somebody",
                "outbids you — then it comes straight back.", "What you win is put in your inventory.");
    }

    @Override
    public String describe() {
        return "the auction house, live";
    }
}
