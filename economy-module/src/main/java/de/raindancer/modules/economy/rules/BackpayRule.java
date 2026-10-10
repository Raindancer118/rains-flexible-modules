package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;
import io.papermc.paper.advancement.AdvancementDisplay;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * What a player is still owed for advancements made before they paid, or before they paid this much: each one's
 * price now, less whatever the ledger says was paid for it already. Never less than nothing.
 */
public final class BackpayRule implements IEconomyRule {

    /** An advancement a player has, by the title it is paid under. */
    public record Made(String title, AdvancementDisplay.Frame frame) {
    }

    /** What is owed in all, and for how many advancements. */
    public record Owed(Money total, int count) {
    }

    public Owed owed(List<Made> made, Map<String, Money> paid, Function<AdvancementDisplay.Frame, Money> price) {
        Money total = Money.ZERO;
        int count = 0;
        for (Made each : made) {
            Money due = price.apply(each.frame()).minus(paid.getOrDefault(each.title(), Money.ZERO));
            if (due.isPositive()) {
                total = total.plus(due);
                count++;
            }
        }
        return new Owed(total, count);
    }

    @Override
    public String describe() {
        return "what advancements made before they paid are still owed";
    }
}
