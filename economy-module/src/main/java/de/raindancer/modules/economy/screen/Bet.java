package de.raindancer.modules.economy.screen;

import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.model.Game;
import de.raindancer.modules.economy.rules.StakeRule;
import de.raindancer.modules.economy.util.Mini;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** The stake a player is playing with, shared between the casino and its games, and the buttons that change it. */
final class Bet {

    private static final StakeRule STAKES = new StakeRule();

    private final EconomyServices services;
    private final Player viewer;
    private Money amount;
    /** The game whose bet limits apply; null in the casino's lobby, where the casino's do. */
    private Game game;

    Bet(EconomyServices services, Player viewer) {
        this.services = services;
        this.viewer = viewer;
        this.amount = STAKES.opening(balance(), least());
    }

    /** The same stake, now at this game and inside its limits; null for the lobby. */
    Bet at(Game game) {
        this.game = game;
        set(amount);
        return this;
    }

    private Money least() {
        return game == null ? services.config().minBetMoney() : services.config().minBet(game);
    }

    Money amount() {
        return amount;
    }

    void set(Money value) {
        this.amount = STAKES.clamp(value, least());
    }

    /** Everything the player has, however much that is. */
    void allIn() {
        set(balance());
    }

    /** The bet slip every game shows: click to type a bet, right click to go all in. */
    org.bukkit.inventory.ItemStack slip(boolean open) {
        java.util.List<String> lore = new java.util.ArrayList<>();
        lore.add("<gray>You have " + Mini.of(services.currency().render(balance())));
        if (open) {
            lore.add("<yellow>Click<gray> to type a bet");
            lore.add("<yellow>Right click<gray> to go all in");
        } else {
            lore.add("<dark_gray>In play");
        }
        return Icons.of(Material.PAPER, "<white>Bet: " + Mini.of(services.currency().render(amount)), lore);
    }

    /**
     * @param reopen opens the same menu again after typing — never a new one, which would start from a new
     *               bet and throw the typed one away
     * @param redraw redraws in place, after going all in
     */
    void onSlip(org.bukkit.event.inventory.InventoryClickEvent click, boolean open, Runnable reopen, Runnable redraw) {
        if (!open) {
            return;
        }
        if (click.isRightClick()) {
            allIn();
            redraw.run();
            return;
        }
        MoneyPrompt.ask(viewer, "Bet how much?", services.currency(), value -> {
            set(value);
            reopen.run();
        }, reopen);
    }

    private Money balance() {
        return services.economy().balance(viewer.getUniqueId());
    }

    /**
     * One band of bet buttons, sized from what the player has: halve, a tenth, a quarter, the bet itself (click to
     * type one), half, all in, double. None goes below the smallest bet, and nothing caps them above.
     */
    void buttons(BetMenu menu, int band, Player viewer) {
        Money least = least();
        Money balance = balance();
        menu.placeBand(band, 1, Icons.of(Material.RED_STAINED_GLASS_PANE, "<red>Halve the bet"), click -> {
            set(Money.of(Math.max(1, amount.minor() / 2)));
            menu.refresh();
        });
        share(menu, band, 2, Material.IRON_NUGGET, "A tenth", STAKES.share(balance, 0.10, least));
        share(menu, band, 3, Material.IRON_INGOT, "A quarter", STAKES.share(balance, 0.25, least));
        menu.placeBand(band, 4, slip(true), click -> onSlip(click, true, menu::reopenAfterPrompt, menu::refresh));
        share(menu, band, 5, Material.GOLD_INGOT, "Half", STAKES.share(balance, 0.5, least));
        share(menu, band, 6, Material.GOLD_BLOCK, "All in", STAKES.share(balance, 1.0, least));
        menu.placeBand(band, 7, Icons.of(Material.LIME_STAINED_GLASS_PANE, "<green>Double the bet"), click -> {
            try {
                set(amount.times(2));
            } catch (ArithmeticException tooBig) {
                // Already as large as a number can be; it stays where it is.
            }
            menu.refresh();
        });
    }

    private void share(BetMenu menu, int band, int column, Material icon, String name, Money value) {
        menu.placeBand(band, column, Icons.of(icon, "<yellow>" + name + ": " + Mini.of(services.currency().render(value)),
                "<gray>Of what you have", "<yellow>Click<gray> to bet that"), click -> {
            set(value);
            menu.refresh();
        });
    }

    /** What a menu showing bet buttons offers — {@code band} is protected on Core's Menu. */
    interface BetMenu {
        void placeBand(int band, int column, org.bukkit.inventory.ItemStack item,
                       java.util.function.Consumer<org.bukkit.event.inventory.InventoryClickEvent> handler);

        void refresh();

        void reopenAfterPrompt();
    }
}
