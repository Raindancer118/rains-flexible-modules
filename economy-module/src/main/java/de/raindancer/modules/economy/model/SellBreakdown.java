package de.raindancer.modules.economy.model;

import de.raindancer.core.social.economy.Money;

import java.util.List;
import java.util.Optional;

/**
 * Why the shop pays one player what it does for an item — each change on the way from the shop's price to
 * theirs, for the lines under "Sell:".
 *
 * @param rolePercent    their own change (a role), with what caused it
 * @param leverPercent   the economy's levers: a low treasury, the stabiliser
 * @param againPercent   less for having sold a lot of it lately, with how many stacks and over how long
 * @param capped         whether it was cut to stay under the cheapest they could buy it for
 * @param budgetLeft     what the shop still pays them today, when it has a daily limit
 */
public record SellBreakdown(int rolePercent, List<String> roleReasons, int leverPercent, int againPercent,
                            int againStacks, int againMinutes, boolean capped, Optional<Money> budgetLeft) {

    public SellBreakdown {
        roleReasons = List.copyOf(roleReasons);
        budgetLeft = budgetLeft == null ? Optional.empty() : budgetLeft;
    }

    public static final SellBreakdown NONE = new SellBreakdown(0, List.of(), 0, 0, 0, 0, false, Optional.empty());
}
