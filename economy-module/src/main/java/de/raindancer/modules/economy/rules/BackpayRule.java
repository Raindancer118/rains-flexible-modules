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

    /** What one advancement is still owed. */
    public record Line(String title, Money due) {
    }

    /** What is owed, advancement by advancement. */
    public record Owed(List<Line> lines) {

        public Owed {
            lines = List.copyOf(lines);
        }

        public Money total() {
            return lines.stream().map(Line::due).reduce(Money.ZERO, Money::plus);
        }

        public int count() {
            return lines.size();
        }
    }

    public Owed owed(List<Made> made, Map<String, Money> paid, Function<AdvancementDisplay.Frame, Money> price) {
        List<Line> lines = new java.util.ArrayList<>();
        for (Made each : made) {
            Money due = price.apply(each.frame()).minus(paid.getOrDefault(each.title(), Money.ZERO));
            if (due.isPositive()) {
                lines.add(new Line(each.title(), due));
            }
        }
        return new Owed(lines);
    }

    @Override
    public String describe() {
        return "what advancements made before they paid are still owed";
    }
}
