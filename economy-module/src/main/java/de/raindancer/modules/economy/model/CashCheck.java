package de.raindancer.modules.economy.model;

import de.raindancer.core.social.economy.Money;

import java.util.List;
import java.util.Map;

/**
 * What a handful of cash is really worth.
 *
 * @param total       what may be credited
 * @param serials     the notes being paid in, each once
 * @param coins       how many coins of each value are being paid in
 * @param taken       per inventory slot, how many items leave it once the credit goes through
 * @param confiscated per slot, how many of those are forgeries and earn nothing
 * @param overTheFloat coins refused because more were offered than are in circulation; left with the player
 */
public record CashCheck(Money total, List<String> serials, Map<Money, Integer> coins, Map<Integer, Integer> taken,
                        Map<Integer, Integer> confiscated, int overTheFloat) {

    public int confiscatedCount() {
        return confiscated.values().stream().mapToInt(Integer::intValue).sum();
    }
}
