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
        set(MenuLayout.HEADER_SUBJECT, Icons.of(Material.GOLD_BLOCK, Mini.of(currency.renderName(true)),
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

        band(MenuLayout.WHO, 2, live.payEnabled() && viewer.hasPermission(PermissionNodes.PAY),
                Icons.of(Material.WRITABLE_BOOK, "<green>Pay somebody",
                        "<gray>Pick a player, then an amount."),
                live.payEnabled() ? NOT_ALLOWED : OFF, click -> pickPayee());
        band(MenuLayout.WHO, 4, Icons.of(Material.BOOK, "<white>Statement",
                "<gray>Everything that went in and out."), click ->
                services.screens().history(viewer, viewer.getUniqueId(), viewer.getName()));
        band(MenuLayout.WHO, 6, live.baltopEnabled() && viewer.hasPermission(PermissionNodes.BALTOP),
                Icons.of(Material.GOLDEN_HELMET, "<gold>Richest players", "<gray>Who has the most."),
                live.baltopEnabled() ? NOT_ALLOWED : OFF, click -> services.screens().baltop(viewer));

        boolean cash = live.cashEnabled() && viewer.hasPermission(PermissionNodes.CASH);
        String cashReason = live.cashEnabled() ? NOT_ALLOWED : OFF;
        band(MenuLayout.RULES, 2, cash, Icons.of(Material.GOLD_NUGGET, "<yellow>Withdraw cash",
                "<gray>Coins and notes you can carry and trade."), cashReason,
                click -> services.screens().withdraw(viewer));
        band(MenuLayout.RULES, 4, cash, Icons.of(Material.HOPPER, "<yellow>Pay in all your cash",
                "<gray>Every coin and note you carry."), cashReason, click -> {
            services.cash().depositAll(viewer);
            refresh();
        });
        band(MenuLayout.RULES, 6, cash && live.chequesEnabled(), Icons.of(Material.PAPER, "<yellow>Write a cheque",
                "<gray>One note for any amount, with your name on it."),
                live.chequesEnabled() ? cashReason : OFF, click -> MoneyPrompt.ask(viewer, "Cheque for how much?",
                        currency, amount -> {
                            services.cash().withdraw(viewer, amount, true);
                            open();
                        }, this::open));

        band(MenuLayout.LAND, 2, live.shopEnabled() && viewer.hasPermission(PermissionNodes.SHOP),
                Icons.of(Material.EMERALD, "<green>Shop", "<gray>Sorted like the creative inventory."),
                live.shopEnabled() ? NOT_ALLOWED : OFF, click -> services.screens().shop(viewer));
        band(MenuLayout.LAND, 4, live.sellingEnabled() && viewer.hasPermission(PermissionNodes.SELL),
                Icons.of(Material.CHEST, "<green>Sell", "<gray>What you carry that the shop buys."),
                live.sellingEnabled() ? NOT_ALLOWED : OFF, click -> services.screens().sell(viewer));
        band(MenuLayout.LAND, 6, live.gamblingEnabled() && viewer.hasPermission(PermissionNodes.GAMBLE),
                Icons.of(Material.GOLD_BLOCK, "<gold>Casino", "<gray>Coin flips, dice, slots, the lottery."),
                live.gamblingEnabled() ? NOT_ALLOWED : OFF, click -> services.screens().casino(viewer));

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
