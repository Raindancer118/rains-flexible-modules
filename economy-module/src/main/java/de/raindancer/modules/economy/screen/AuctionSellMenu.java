package de.raindancer.modules.economy.screen;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.model.PriceTag;
import de.raindancer.modules.economy.util.Mini;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.List;
import java.util.TreeSet;

/**
 * Putting one stack from the inventory up for auction: the starting price (offered at what the shop pays for
 * it), a buy-it-now price if the seller wants one, and how long it runs.
 */
public final class AuctionSellMenu extends Menu implements IEconomyScreen {

    private static final int[] LENGTHS = {60, 120, 300, 600, 1800, 3600};

    private final EconomyServices services;
    private final int slot;
    private final ItemStack picked;
    private Money start;
    private Money buyout = Money.ZERO;
    private int seconds;

    AuctionSellMenu(EconomyServices services, Player viewer, Menu parent, int slot, ItemStack picked) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.slot = slot;
        this.picked = picked.clone();
        EconomySettings live = services.config();
        PriceTag tag = services.shop().tag(picked.getType());
        Money shop = tag.sellable() ? tag.sell().times(picked.getAmount()) : Money.ZERO;
        this.start = shop.max(live.auctionSmallestStartMoney());
        this.seconds = live.auctionDefaultSeconds();
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Auction this");
    }

    @Override
    public String breadcrumb() {
        return "Auction this";
    }

    @Override
    protected void render() {
        EconomySettings live = services.config();
        Currency currency = services.currency();
        set(MenuLayout.HEADER_SUBJECT, picked.clone());
        set(MenuLayout.HEADER_RIGHT, Icons.of(Material.IRON_BARS, "<white>Fees",
                "<gray>Listing: " + Mini.of(currency.render(live.auctionListingFeeMoney())),
                "<gray>The house keeps " + CasinoMenu.percent(live.auctionFee() / 100) + "% of the price"));

        band(MenuLayout.RULES, 2, Icons.of(Material.GOLD_NUGGET, "<yellow>Starting price: " + Mini.of(currency.render(start)),
                "<gray>At least " + Mini.of(currency.render(live.auctionSmallestStartMoney())),
                "<yellow>Click<gray> to type one"), click -> MoneyPrompt.ask(viewer, "Starting price?", currency,
                amount -> {
                    start = amount;
                    open();
                }, this::open));
        band(MenuLayout.RULES, 4, Icons.of(Material.GOLD_BLOCK, "<gold>Buy it now: "
                        + (buyout.isPositive() ? Mini.of(currency.render(buyout)) : "<gray>none"),
                "<gray>Whoever pays this ends it at once.", "<yellow>Click<gray> to type one",
                "<yellow>Right click<gray> for none"), click -> {
            if (click.isRightClick()) {
                buyout = Money.ZERO;
                refresh();
                return;
            }
            MoneyPrompt.ask(viewer, "Buy it now for?", currency, amount -> {
                buyout = amount;
                open();
            }, this::open);
        });
        band(MenuLayout.RULES, 6, Icons.of(Material.CLOCK, "<white>Runs for " + Times.describe(Duration.ofSeconds(seconds)),
                "<yellow>Click<gray> for longer", "<yellow>Right click<gray> for shorter"), click -> {
            seconds = next(lengths(live), seconds, !click.isRightClick());
            refresh();
        });

        toolbar(4, Icons.of(Material.LIME_CONCRETE, "<green>Put it up",
                "<gray>Starts at " + Mini.of(currency.render(start)),
                buyout.isPositive() ? "<gray>Buy it now: " + Mini.of(currency.render(buyout)) : "",
                "<dark_gray>It waits in the queue if one is running."), click -> {
            if (services.auctions().list(viewer, slot, picked, start, buyout, seconds)) {
                AuctionMenu.open(services, viewer, null);
            } else {
                refresh();
            }
        });
    }

    /** The lengths a seller may choose: the usual ones inside the server's limits, and the default. */
    private static List<Integer> lengths(EconomySettings live) {
        int least = live.auctionMinSeconds();
        int most = Math.max(least, live.auctionMaxSeconds());
        TreeSet<Integer> all = new TreeSet<>();
        for (int each : LENGTHS) {
            if (each >= least && each <= most) {
                all.add(each);
            }
        }
        all.add(Math.max(least, Math.min(most, live.auctionDefaultSeconds())));
        all.add(least);
        all.add(most);
        return List.copyOf(all);
    }

    private static int next(List<Integer> lengths, int now, boolean longer) {
        int at = lengths.indexOf(now);
        if (at < 0) {
            return lengths.getFirst();
        }
        return lengths.get(Math.max(0, Math.min(lengths.size() - 1, at + (longer ? 1 : -1))));
    }

    @Override
    protected List<String> helpLines() {
        return List.of("Set the price and the length,", "then put it up. The item leaves your", "inventory when you do.");
    }

    @Override
    public String describe() {
        return "putting one stack from the inventory up for auction";
    }
}
