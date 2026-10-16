package de.raindancer.modules.guilds.screen;

import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.prompt.AnvilInput;
import de.raindancer.core.ui.prompt.Parsed;
import de.raindancer.modules.guilds.GuildsServices;
import org.bukkit.entity.Player;

import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Typing an amount of money into an anvil — "2500", "12.50", "1.5k" — checked against a most while typing, so the
 * player sees "more than you may take" before clicking, not after.
 */
final class AmountPrompt {

    private AmountPrompt() {
    }

    /** @param most the most that may be typed, in minor units, read on every keystroke; negative for no most */
    static void ask(GuildsServices services, Player viewer, String title, LongSupplier most, String tooMuch,
                    Consumer<Money> answer, Runnable cancelled) {
        AnvilInput.open(viewer, title, "", typed -> {
            var amount = services.bank().parse(typed);
            if (amount.isEmpty()) {
                return Parsed.no("Not an amount — try 2500, 12.50 or 1.5k.");
            }
            long limit = most.getAsLong();
            if (limit >= 0 && amount.get().minor() > limit) {
                return Parsed.no(tooMuch + " (" + services.bank().format(limit) + ")");
            }
            return Parsed.ok(amount.get());
        }, answer, cancelled);
    }
}
