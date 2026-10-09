package de.raindancer.modules.jobs.model;

import de.raindancer.core.ui.choose.ItemSelection;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One goal on the board, and who has given what towards it. Immutable; the book replaces it whole.
 *
 * @param number counts up across all goals, so staff can name one
 * @param given  how much each player has given, in the order they first did
 */
public record Goal(long number, String template, String title, String icon, GoalKind kind, ItemSelection items,
                   int amount, long startedAt, long endsAt, Map<UUID, Integer> given) {

    public Goal {
        given = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(given));
    }

    public int progress() {
        return (int) Math.min(Integer.MAX_VALUE, given.values().stream().mapToLong(Integer::longValue).sum());
    }

    public int left() {
        return Math.max(0, amount - progress());
    }

    public boolean reached() {
        return progress() >= amount;
    }

    public int givenBy(UUID player) {
        return given.getOrDefault(player, 0);
    }

    /** Adds what one player gave; never past what the goal still needs. */
    public Goal with(UUID player, int count) {
        int taken = Math.min(Math.max(0, count), left());
        Map<UUID, Integer> next = new LinkedHashMap<>(given);
        if (taken > 0) {
            next.merge(player, taken, Integer::sum);
        }
        return new Goal(number, template, title, icon, kind, items, amount, startedAt, endsAt, next);
    }

    /** Who gave most, most first. */
    public List<Map.Entry<UUID, Integer>> leaders(int how) {
        return given.entrySet().stream().sorted(Map.Entry.<UUID, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(how).toList();
    }
}
