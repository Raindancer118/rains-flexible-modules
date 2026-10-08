package de.raindancer.modules.economy.model;

import de.raindancer.core.social.economy.Money;

import java.util.Map;

/**
 * An amount broken into pieces of cash.
 *
 * @param pieces   how many of each, largest first
 * @param leftover what no denomination could make — stays in the account
 */
public record Split(Map<Denomination, Integer> pieces, Money leftover) {

    public int count() {
        return pieces.values().stream().mapToInt(Integer::intValue).sum();
    }

    public Money paidOut() {
        Money total = Money.ZERO;
        for (Map.Entry<Denomination, Integer> each : pieces.entrySet()) {
            total = total.plus(each.getKey().value().times(each.getValue()));
        }
        return total;
    }
}
