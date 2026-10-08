package de.raindancer.modules.economy.screen;

import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.modules.economy.EconomyServices;
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

    Bet(EconomyServices services, Player viewer) {
        this.services = services;
        this.viewer = viewer;
        this.amount = STAKES.opening(balance(), services.config().minBetMoney(), services.config().maxBetMoney());
    }

    Money amount() {
        return amount;
    }

    void set(Money value) {
        this.amount = STAKES.clamp(value, services.config().minBetMoney(), services.config().maxBetMoney());
    }

    private Money balance() {
        return services.economy().balance(viewer.getUniqueId());
    }

    /**
     * One band of bet buttons, sized from what the player has: halve, a tenth, a quarter, the bet itself (click to
     * type one), half, all in, double. Each stays within the server's smallest and largest bet.
     */
    void buttons(BetMenu menu, int band, Player viewer) {
        Money least = services.config().minBetMoney();
        Money most = services.config().maxBetMoney();
        Money balance = balance();
        menu.placeBand(band, 1, Icons.of(Material.RED_STAINED_GLASS_PANE, "<red>Halve the bet"), click -> {
            set(Money.of(Math.max(1, amount.minor() / 2)));
            menu.refresh();
        });
        share(menu, band, 2, Material.IRON_NUGGET, "A tenth", STAKES.share(balance, 0.10, least, most));
        share(menu, band, 3, Material.IRON_INGOT, "A quarter", STAKES.share(balance, 0.25, least, most));
        menu.placeBand(band, 4, Icons.of(Material.PAPER, "<white>Bet: " + Mini.of(services.currency().render(amount)),
                "<gray>You have " + Mini.of(services.currency().render(balance)),
                most.isPositive() ? "<dark_gray>Largest bet here: " + Mini.of(services.currency().render(most)) : "",
                "<yellow>Click<gray> to type a bet"), click -> MoneyPrompt.ask(viewer, "Bet how much?",
                services.currency(), value -> {
                    set(value);
                    menu.reopenAfterPrompt();
                }, menu::reopenAfterPrompt));
        share(menu, band, 5, Material.GOLD_INGOT, "Half", STAKES.share(balance, 0.5, least, most));
        share(menu, band, 6, Material.GOLD_BLOCK, "All in", STAKES.share(balance, 1.0, least, most));
        menu.placeBand(band, 7, Icons.of(Material.LIME_STAINED_GLASS_PANE, "<green>Double the bet"), click -> {
            try {
                set(amount.times(2));
            } catch (ArithmeticException tooBig) {
                // Already as large as anything can be; the maximum bet keeps it there.
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
