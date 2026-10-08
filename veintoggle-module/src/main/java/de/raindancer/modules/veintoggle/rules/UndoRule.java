package de.raindancer.modules.veintoggle.rules;

import de.raindancer.core.social.economy.Money;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Which blocks of a vein an undo puts back. A block goes back only where nothing else stands now, and
 * only if everything it dropped can be taken back — otherwise undo hands out a second set of diamonds.
 * Blocks are judged in order, each against what the earlier ones left of the pool, so a player who
 * has used some of the drops still gets the rest of the vein back.
 */
public final class UndoRule {

    /** How long a vein has to have stopped growing before it counts as finished. */
    public static final long SETTLED_AFTER_MILLIS = 1_000L;

    /**
     * @param restore   the blocks to put back
     * @param inTheWay  the blocks with something in their place now
     * @param unpaid    the blocks whose drops can no longer all be taken back
     * @param toTake    how many of each kind of item to take for {@code restore}
     */
    public record Plan<B, K>(List<B> restore, List<B> inTheWay, List<B> unpaid, Map<K, Integer> toTake) {
    }

    public <B, K> Plan<B, K> plan(List<B> blocks, Predicate<B> free, Function<B, Map<K, Integer>> needs,
                                  Map<K, Integer> available) {
        Map<K, Integer> left = new HashMap<>(available);
        Map<K, Integer> toTake = new HashMap<>();
        List<B> restore = new ArrayList<>();
        List<B> inTheWay = new ArrayList<>();
        List<B> unpaid = new ArrayList<>();
        for (B block : blocks) {
            if (!free.test(block)) {
                inTheWay.add(block);
                continue;
            }
            Map<K, Integer> need = needs.apply(block);
            boolean paid = need.entrySet().stream()
                    .allMatch(item -> left.getOrDefault(item.getKey(), 0) >= item.getValue());
            if (!paid) {
                unpaid.add(block);
                continue;
            }
            need.forEach((kind, amount) -> {
                left.merge(kind, -amount, Integer::sum);
                toTake.merge(kind, amount, Integer::sum);
            });
            restore.add(block);
        }
        return new Plan<>(List.copyOf(restore), List.copyOf(inTheWay), List.copyOf(unpaid), Map.copyOf(toTake));
    }

    /** Whether a vein last added to at {@code changedAt} has finished coming down. */
    public boolean settled(long changedAt, long now) {
        return now - changedAt >= SETTLED_AFTER_MILLIS;
    }

    /** Whether it is too late to undo it. A window of zero means undo is switched off. */
    public boolean expired(long changedAt, long now, long windowMillis) {
        return windowMillis <= 0 || now - changedAt > windowMillis;
    }

    /**
     * How many of each wanted item {@code balance} pays for — whole items only, kinds in the order
     * given, and nothing for a kind without a price.
     */
    public <K> Map<K, Integer> affordable(Map<K, Integer> wanted, Function<K, Optional<Money>> price, Money balance) {
        Map<K, Integer> units = new LinkedHashMap<>();
        long left = Math.max(0, balance.minor());
        for (Map.Entry<K, Integer> want : wanted.entrySet()) {
            Optional<Money> each = price.apply(want.getKey());
            if (each.isEmpty() || !each.get().isPositive() || want.getValue() <= 0) {
                continue;
            }
            int count = (int) Math.min(want.getValue(), left / each.get().minor());
            if (count > 0) {
                units.put(want.getKey(), count);
                left -= count * each.get().minor();
            }
        }
        return units;
    }

    /** What all of {@code wanted} costs, counting only kinds that have a price. */
    public <K> Money cost(Map<K, Integer> wanted, Function<K, Optional<Money>> price) {
        Money total = Money.ZERO;
        for (Map.Entry<K, Integer> want : wanted.entrySet()) {
            Optional<Money> each = price.apply(want.getKey());
            if (each.isPresent() && want.getValue() > 0) {
                total = total.plus(each.get().times(want.getValue()));
            }
        }
        return total;
    }
}
