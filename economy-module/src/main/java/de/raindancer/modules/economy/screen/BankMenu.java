package de.raindancer.modules.economy.screen;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.choose.PlayerChooser;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.util.Mini;
import de.raindancer.modules.economy.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** {@code /bank}: the balance, and a door to everything money does. */
public final class BankMenu extends Menu implements IEconomyScreen {

    static final String OFF = "Switched off on this server.";
    static final String NOT_ALLOWED = "You may not use this.";

    private final EconomyServices services;

    public BankMenu(EconomyServices services, Player viewer) {
        super(viewer, services.brand(), null);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Bank");
    }

    @Override
    public String breadcrumb() {
        return "Bank";
    }

    @Override
    protected void render() {
        EconomySettings live = services.config();
        Currency currency = services.currency();
        Money balance = services.economy().balance(viewer.getUniqueId());
        int place = services.leaderboard().placeOf(viewer.getUniqueId());

        set(MenuLayout.HEADER_LEFT, Icons.head(viewer, "<white>" + viewer.getName(),
                "<gray>Balance: " + Mini.of(currency.render(balance)),
                place > 0 ? "<dark_gray>#" + place + " on the server" : ""));
        set(MenuLayout.HEADER_SUBJECT, Icons.of(services.cash().coinMaterial(), Mini.of(currency.renderName(true)),
                "<gray>One " + currency.singular() + " is written " + Mini.of(currency.render(currency.ofMajor(1))),
                "<dark_gray>Your money is safe in here. Cash you carry is not."));
        boolean dailyReady = services.daily().ready(viewer);
        set(MenuLayout.HEADER_RIGHT, Icons.of(dailyReady ? Material.SUNFLOWER : Material.CLOCK,
                        dailyReady ? "<yellow>Daily reward ready" : "<gray>Daily reward",
                        !live.dailyEnabled() ? "<red>" + OFF
                                : dailyReady ? "<gray>Click to claim it." : "<gray>Back in "
                                + services.daily().hoursLeft() + " hour(s)."),
                click -> {
                    if (live.dailyEnabled() && viewer.hasPermission(PermissionNodes.DAILY)) {
                        services.daily().claim(viewer);
                        refresh();
                    } else {
                        no(live.dailyEnabled() ? NOT_ALLOWED : OFF);
                    }
                });

        band(MenuLayout.WHO, 1, live.payEnabled() && viewer.hasPermission(PermissionNodes.PAY),
                Icons.of(Material.WRITABLE_BOOK, "<green>Pay somebody",
                        "<gray>Pick a player, then an amount."),
                live.payEnabled() ? NOT_ALLOWED : OFF, click -> pickPayee());
        band(MenuLayout.WHO, 3, Icons.of(Material.WRITTEN_BOOK, "<white>Statement",
                "<gray>Everything that went in and out, as a book.", "",
                "<yellow>Click<gray> to read it", "<yellow>Shift click<gray> to print a copy to keep"), click -> {
            if (click.isShiftClick()) {
                services.statements().print(viewer);
                services.messages().send(viewer, "economy.statement.printed");
            } else {
                services.statements().open(viewer, viewer.getUniqueId(), viewer.getName());
            }
        });
        band(MenuLayout.WHO, 5, live.hireEnabled() && viewer.hasPermission(PermissionNodes.HIRE),
                Icons.of(Material.IRON_PICKAXE, "<white>Jobs", "<gray>Who you employ, and who employs you.",
                        "<dark_gray>/hire <player> <wage> <every> [job]"),
                live.hireEnabled() ? NOT_ALLOWED : OFF, click -> services.screens().jobs(viewer));
        band(MenuLayout.WHO, 7, live.baltopEnabled() && viewer.hasPermission(PermissionNodes.BALTOP),
                Icons.of(Material.GOLDEN_HELMET, "<gold>Richest players", "<gray>Who has the most."),
                live.baltopEnabled() ? NOT_ALLOWED : OFF, click -> services.screens().baltop(viewer));

        boolean cash = live.cashEnabled() && viewer.hasPermission(PermissionNodes.CASH);
        String cashReason = live.cashEnabled() ? NOT_ALLOWED : OFF;
        band(MenuLayout.RULES, 1, cash, Icons.of(services.cash().coinMaterial(), "<yellow>Withdraw cash",
                "<gray>Coins you can carry and trade."), cashReason,
                click -> services.screens().withdraw(viewer));
        band(MenuLayout.RULES, 3, cash, Icons.of(Material.HOPPER, "<yellow>Pay in all your cash",
                "<gray>Every coin and cheque you carry."), cashReason, click -> {
            services.cash().depositAll(viewer);
            refresh();
        });
        band(MenuLayout.RULES, 5, cash && live.chequesEnabled(), Icons.of(Material.PAPER, "<yellow>Write a cheque",
                "<gray>One signed paper for any amount."),
                live.chequesEnabled() ? cashReason : OFF, click -> MoneyPrompt.ask(viewer, "Cheque for how much?",
                        currency, amount -> {
                            services.cash().withdraw(viewer, amount, true);
                            open();
                        }, this::open));

        var loan = services.loans().loanOf(viewer.getUniqueId());
        band(MenuLayout.RULES, 7, (live.loansEnabled() || loan.isPresent()) && viewer.hasPermission(PermissionNodes.LOAN),
                Icons.of(Material.GOLD_INGOT, "<gold>Loan", loan.isPresent()
                                ? "<gray>You owe " + Mini.of(currency.render(loan.get().owed()))
                                : "<gray>Borrow from the bank.",
                        loan.isPresent() ? "<gray>Due in " + services.loans().dueIn(loan.get()) : ""),
                live.loansEnabled() ? NOT_ALLOWED : OFF, click -> services.screens().loan(viewer));
        band(MenuLayout.LAND, 1, live.shopEnabled() && viewer.hasPermission(PermissionNodes.SHOP),
                Icons.of(Material.EMERALD, "<green>Shop", "<gray>Sorted like the creative inventory."),
                live.shopEnabled() ? NOT_ALLOWED : OFF, click -> services.screens().shop(viewer));
        band(MenuLayout.LAND, 3, live.sellingEnabled() && viewer.hasPermission(PermissionNodes.SELL),
                Icons.of(Material.CHEST, "<green>Sell", "<gray>What you carry that the shop buys."),
                live.sellingEnabled() ? NOT_ALLOWED : OFF, click -> services.screens().sell(viewer));
        band(MenuLayout.LAND, 5, live.auctionsEnabled() && viewer.hasPermission(PermissionNodes.AUCTION),
                Icons.of(Material.BELL, "<gold>Auction house", "<gray>Bid on what players put up,",
                        "<gray>or sell to the highest bidder."),
                live.auctionsEnabled() ? NOT_ALLOWED : OFF, click -> services.screens().auctions(viewer));
        band(MenuLayout.LAND, 7, live.gamblingEnabled() && viewer.hasPermission(PermissionNodes.GAMBLE),
                Icons.of(Material.GOLD_BLOCK, "<gold>Casino", "<gray>Coin flips, dice, slots, roulette, the lottery."),
                live.gamblingEnabled() ? NOT_ALLOWED : OFF, click -> services.screens().casino(viewer));

        boolean shown = de.raindancer.modules.economy.service.SidebarService.SHOWN.isOn(viewer);
        toolbar(2, live.sidebarEnabled(), Icons.of(shown ? Material.OAK_SIGN : Material.BIRCH_SIGN,
                shown ? "<white>Sidebar: <green>shown" : "<white>Sidebar: <gray>hidden",
                "<gray>Your balance and the richest players", "<gray>on the right of the screen.",
                "<yellow>Click<gray> to " + (shown ? "hide" : "show") + " it"), OFF, click -> {
            services.sidebar().toggle(viewer);
            services.sidebar().refresh(java.util.List.of(viewer));
            refresh();
        });
        if (viewer.hasPermission(PermissionNodes.ADMIN)) {
            toolbar(4, Icons.of(Material.COMMAND_BLOCK, "<red>Run the economy",
                    "<gray>Switch features, close shop categories,", "<gray>paint the currency."),
                    click -> services.screens().admin(viewer));
        }
    }

    private void pickPayee() {
        new PlayerChooser(viewer, services.brand(), this, "Pay who?", List.of(viewer.getUniqueId()), entry ->
                MoneyPrompt.ask(viewer, "Pay " + entry.name() + " how much?", services.currency(), amount -> {
                    services.payments().pay(viewer, services.server().getOfflinePlayer(entry.id()), amount, false);
                    open();
                }, this::open)).open();
    }

    private void no(String why) {
        services.messages().send(viewer, "economy.menu.locked", "reason", why);
        services.effects().play(viewer.getUniqueId(), Cues.NO);
    }

    @Override
    protected List<String> helpLines() {
        return List.of("Your account. Money in here is safe;",
                "cash you withdraw is an item like any other,",
                "and is lost if you lose it.",
                "",
                "Right click cash to pay it back in.");
    }

    @Override
    public String describe() {
        return "the balance, and a door to everything money does";
    }
}
