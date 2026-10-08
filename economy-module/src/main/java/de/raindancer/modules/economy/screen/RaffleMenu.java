package de.raindancer.modules.economy.screen;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuAnimation;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.model.Raffle;
import de.raindancer.modules.economy.util.Mini;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Every raffle running: the prize, the ticket price, how many are sold, your share and the time left —
 * click for a ticket, right click for ten. And the way to raffle off what you hold.
 */
public final class RaffleMenu extends PaginatedMenu<Raffle> implements IEconomyScreen {

    private final EconomyServices services;
    private final Menu back;

    RaffleMenu(EconomyServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.back = parent;
    }

    public static void open(EconomyServices services, Player viewer, Menu parent) {
        RaffleMenu menu = new RaffleMenu(services, viewer, parent);
        menu.open();
        MenuAnimation.loop(services.plugin(), menu, 20L, menu::refresh, () -> { });
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Raffles and giveaways");
    }

    @Override
    public String breadcrumb() {
        return "Raffles";
    }

    @Override
    protected List<Raffle> entries() {
        return services.raffles().raffles();
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.NAME_TAG, "<gray>No raffle running", "<gray>Start one below");
    }

    @Override
    protected ItemStack icon(Raffle raffle) {
        Currency currency = services.currency();
        ItemStack item = services.raffles().prize(raffle);
        int mine = raffle.ticketsOf(viewer.getUniqueId());
        List<Component> lore = new ArrayList<>(raffle.moneyPrize() ? List.of()
                : Optional.ofNullable(item.lore()).orElse(List.of()));
        if (raffle.moneyPrize()) {
            item.editMeta(meta -> meta.displayName(line("<gold>" + Mini.of(currency.render(raffle.prize())))));
        }
        lore.add(Component.empty());
        boolean yours = viewer.getUniqueId().equals(raffle.host());
        if (raffle.giveaway()) {
            lore.add(line("<aqua>Giveaway #" + raffle.number() + " <gray>by <white>" + raffle.hostName()));
            lore.add(line("<gray>Free to join · " + raffle.sold() + " joined"));
            lore.add(line("<gray>Drawn in " + Times.describe(services.raffles().left(raffle))));
            if (mine > 0) {
                lore.add(line("<green>You are in · " + String.format("%.1f%%",
                        services.raffles().rule().chance(mine, raffle.sold()) * 100) + " chance"));
            } else if (!yours) {
                lore.add(line("<yellow>Click<gray> to join"));
            }
        } else {
            lore.add(line("<light_purple>Raffle #" + raffle.number() + " <gray>by <white>" + raffle.hostName()));
            lore.add(line("<gray>A ticket: " + Mini.of(currency.render(raffle.ticketPrice())) + " <gray>· "
                    + raffle.sold() + (raffle.mostTickets() > 0 ? " of " + raffle.mostTickets() : "") + " sold"));
            lore.add(line("<gray>Drawn in " + Times.describe(services.raffles().left(raffle))));
            if (mine > 0) {
                lore.add(line("<green>You hold " + mine + " · " + String.format("%.1f%%",
                        services.raffles().rule().chance(mine, raffle.sold()) * 100) + " chance"));
            }
            if (!yours) {
                lore.add(line("<yellow>Click<gray> for a ticket, <yellow>right click<gray> for ten"));
            }
        }
        item.lore(lore);
        return item;
    }

    @Override
    protected void onClick(Raffle raffle, InventoryClickEvent event) {
        services.raffles().buy(viewer, raffle.number(), event.isRightClick() && !raffle.giveaway() ? 10 : 1);
        refresh();
    }

    @Override
    protected void render() {
        super.render();
        Currency currency = services.currency();
        toolbar(1, Icons.of(Material.CHEST, "<green>Raffle off what you hold",
                "<gray>Puts the item in your main hand up.",
                "<gray>Starting costs " + Mini.of(currency.render(services.config().raffleListingFeeMoney())) + "<gray>.",
                "<gray>Length and limits: <white>/raffle start <price> [30m]"), click -> {
            if (viewer.getInventory().getItemInMainHand().getType().isAir()) {
                services.messages().send(viewer, "economy.raffle.empty-hand");
                return;
            }
            MoneyPrompt.ask(viewer, "Price of a ticket?", currency, price -> {
                services.raffles().start(viewer, price, 0, 0, 0);
                open(services, viewer, back);
            }, () -> open(services, viewer, back));
        });
        toolbar(3, Icons.of(Material.GOLD_BLOCK, "<gold>Raffle off money",
                "<gray>From your account; the winner gets it.", "<gray>You get the tickets' money.",
                "<gray>Length and limits: <white>/raffle money <prize> <price> [30m]"), click ->
                MoneyPrompt.ask(viewer, "How much to raffle off?", currency, prize ->
                        MoneyPrompt.ask(viewer, "Price of a ticket?", currency, price -> {
                            services.raffles().startMoney(viewer, prize, price, 0, 0, 0);
                            open(services, viewer, back);
                        }, () -> open(services, viewer, back)), () -> open(services, viewer, back)));
        toolbar(5, Icons.of(Material.CAKE, "<aqua>Give away what you hold",
                "<gray>Free for everybody to join, once each.", "<gray>Length: <white>/giveaway start [30m]"), click -> {
            services.raffles().giveItem(viewer, 0);
            refresh();
        });
        toolbar(7, Icons.of(Material.GOLD_NUGGET, "<aqua>Give away money",
                "<gray>From your account.", "<gray>Length: <white>/giveaway money <amount> [30m]"), click ->
                MoneyPrompt.ask(viewer, "How much to give away?", currency, prize -> {
                    services.raffles().giveMoney(viewer, prize, 0);
                    open(services, viewer, back);
                }, () -> open(services, viewer, back)));
    }

    private static Component line(String text) {
        return MiniMessage.miniMessage().deserialize(text).decoration(TextDecoration.ITALIC, false);
    }

    @Override
    protected List<String> helpLines() {
        return List.of("Buy tickets; when a raffle ends, one is drawn.", "Each ticket is one equal chance.",
                "What you win is put in your inventory.");
    }

    @Override
    public String describe() {
        return "the raffles running";
    }
}
