package de.raindancer.modules.economy.screen;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.prompt.AnvilInput;
import de.raindancer.core.ui.prompt.Parsed;
import org.bukkit.entity.Player;

import java.util.function.Consumer;

/** Typing an amount of money into an anvil — "12.50", "1.5k", "$20" — read through the currency. */
final class MoneyPrompt {

    private MoneyPrompt() {
    }

    static void ask(Player player, String title, Currency currency, Consumer<Money> answer, Runnable cancelled) {
        AnvilInput.open(player, title, "", typed -> currency.parse(typed)
                        .filter(Money::isPositive)
                        .map(Parsed::ok)
                        .orElseGet(() -> Parsed.no("That is not an amount — try 12, 12.50 or 1.5k.")),
                answer, cancelled);
    }
}
