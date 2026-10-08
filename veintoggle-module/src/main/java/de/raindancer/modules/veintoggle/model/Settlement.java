package de.raindancer.modules.veintoggle.model;

import de.raindancer.core.social.economy.Money;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything an undo has taken so far to pay for a vein, in the order it was taken — which is the order
 * of who should pay first. Whatever turns out not to be needed goes back in the opposite order: the
 * undoer's own money first, the ground last.
 *
 * @param <K> a kind of item
 */
public final class Settlement<K> {

    /** Where something was taken from, in the order sources are asked. */
    public enum Source {
        /** The vein's own items still lying around. */
        GROUND,
        /** The undoer's inventory. */
        UNDOER_ITEMS,
        /** A collector's inventory. */
        COLLECTOR_ITEMS,
        /** A collector's account, for items they no longer had. */
        COLLECTOR_MONEY,
        /** The undoer's account, for everything nobody else could cover. */
        UNDOER_MONEY
    }

    /**
     * @param from      whose — null for the ground
     * @param units     how many items it covers
     * @param unitPrice what each cost, for money; zero for items
     */
    public record Taking<K>(Source source, UUID from, K kind, int units, Money unitPrice) {

        public Money total() {
            return unitPrice.times(units);
        }
    }

    private final List<Taking<K>> takings = new ArrayList<>();

    public synchronized void add(Taking<K> taking) {
        if (taking.units() > 0) {
            takings.add(taking);
        }
    }

    public synchronized List<Taking<K>> takings() {
        return List.copyOf(takings);
    }

    /** How many of each kind have been covered, by items or by money. */
    public synchronized Map<K, Integer> supply() {
        Map<K, Integer> supply = new HashMap<>();
        takings.forEach(taking -> supply.merge(taking.kind(), taking.units(), Integer::sum));
        return supply;
    }

    /**
     * What to give back once {@code used} is known: the surplus of each kind, newest taking first.
     * The result is what is refunded; whatever is not in it was spent.
     */
    public synchronized List<Taking<K>> refunds(Map<K, Integer> used) {
        Map<K, Integer> surplus = supply();
        used.forEach((kind, amount) -> surplus.merge(kind, -amount, Integer::sum));
        List<Taking<K>> back = new ArrayList<>();
        for (int i = takings.size() - 1; i >= 0; i--) {
            Taking<K> taking = takings.get(i);
            int extra = surplus.getOrDefault(taking.kind(), 0);
            if (extra <= 0) {
                continue;
            }
            int units = Math.min(extra, taking.units());
            surplus.put(taking.kind(), extra - units);
            back.add(new Taking<>(taking.source(), taking.from(), taking.kind(), units, taking.unitPrice()));
        }
        return back;
    }
}
