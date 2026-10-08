package de.raindancer.modules.economy.screen;

import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.util.Mini;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** The stake a player is playing with, shared between the casino and its games, and the buttons that change it. */
final class Bet {

    private final EconomyServices services;
    private Money amount;

    Bet(EconomyServices services) {
        this.services = services;
        Money least = services.config().minBetMoney();
        this.amount = least.isPositive() ? least.times(10).min(services.config().maxBetMoney().isPositive()
                ? services.config().maxBetMoney() : least.times(10)) : services.currency().ofMajor(10);
    }

    Money amount() {
        return amount;
    }

    void set(Money value) {
        Money least = services.config().minBetMoney();
        Money most = services.config().maxBetMoney();
        Money next = value.max(least.isPositive() ? least : Money.of(1));
        this.amount = most.isPositive() ? next.min(most) : next;
    }

    /** Halve, the amount (click to type), double — in one band of the given menu. */
    void buttons(BetMenu menu, int band, Player viewer) {
        menu.placeBand(band, 2, Icons.of(Material.RED_STAINED_GLASS_PANE, "<red>Halve the bet"), click -> {
            set(Money.of(Math.max(1, amount.minor() / 2)));
            menu.refresh();
        });
        menu.placeBand(band, 4, Icons.of(Material.PAPER, "<white>Bet: " + Mini.of(services.currency().render(amount)),
                "<yellow>Click<gray> to type a bet"), click -> MoneyPrompt.ask(viewer, "Bet how much?",
                services.currency(), value -> {
                    set(value);
                    menu.reopenAfterPrompt();
                }, menu::reopenAfterPrompt));
        menu.placeBand(band, 6, Icons.of(Material.LIME_STAINED_GLASS_PANE, "<green>Double the bet"), click -> {
            try {
                set(amount.times(2));
            } catch (ArithmeticException tooBig) {
                // Already as large as anything can be; the maximum bet keeps it there.
            }
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
