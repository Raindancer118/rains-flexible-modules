package de.raindancer.modules.economy.model;

import de.raindancer.core.social.economy.Money;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Money printed and money destroyed over a stretch of time, by where it came from or went.
 *
 * @param created   minor units by label, a kind's name or another plugin's source
 * @param destroyed the same, as positive amounts
 */
public record Flows(Map<String, Long> created, Map<String, Long> destroyed) {

    public static final Flows NONE = new Flows(Map.of(), Map.of());

    public Flows {
        created = Map.copyOf(created);
        destroyed = Map.copyOf(destroyed);
    }

    public Money totalCreated() {
        return Money.of(created.values().stream().mapToLong(Long::longValue).sum());
    }

    public Money totalDestroyed() {
        return Money.of(destroyed.values().stream().mapToLong(Long::longValue).sum());
    }

    /** Biggest first. */
    public static List<Map.Entry<String, Long>> ranked(Map<String, Long> flows) {
        return flows.entrySet().stream().filter(each -> each.getValue() > 0)
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())).toList();
    }
}
