package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.Denomination;
import de.raindancer.modules.economy.model.Split;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An amount broken into the fewest pieces of cash: largest first. Greedy is exact for every sensible set
 * of denominations (1, 5, 10, 50…); for an odd one it may use a piece more than strictly needed, never
 * pay out more than asked.
 */
public final class ChangeRule implements IEconomyRule {

    public Split split(Money amount, List<Denomination> denominations) {
        List<Denomination> largestFirst = new ArrayList<>(denominations);
        largestFirst.sort(Comparator.comparing(Denomination::value).reversed());
        Map<Denomination, Integer> pieces = new LinkedHashMap<>();
        long left = Math.max(0, amount.minor());
        for (Denomination each : largestFirst) {
            long value = each.value().minor();
            if (value <= 0) {
                continue;
            }
            long count = left / value;
            if (count > 0) {
                int clipped = (int) Math.min(Integer.MAX_VALUE, count);
                pieces.put(each, clipped);
                left -= value * clipped;
            }
        }
        return new Split(pieces, Money.of(left));
    }

    @Override
    public String describe() {
        return "an amount broken into pieces of cash, largest first";
    }
}
