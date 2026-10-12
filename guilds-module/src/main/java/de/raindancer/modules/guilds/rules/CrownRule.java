package de.raindancer.modules.guilds.rules;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Who earned the most experience in a week. Ties go to the lower guild id, so the answer never depends on map
 * order; a guild that earned nothing is never placed.
 */
public final class CrownRule implements IGuildsRule {

    public record Place(String guild, long xp) {
    }

    @Override
    public String describe() {
        return "the guild with the most experience earned in a week wears the crown the next week";
    }

    public List<Place> podium(Map<String, Long> weekXp, int places) {
        return weekXp.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue() > 0)
                .map(entry -> new Place(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingLong(Place::xp).reversed().thenComparing(Place::guild))
                .limit(Math.max(0, places))
                .toList();
    }
}
