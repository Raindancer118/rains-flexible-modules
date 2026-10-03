package de.raindancer.modules.speedrun.manhunt.stats;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * An Elo-style rating for an asymmetric game: one number per player, two sides of any size.
 *
 * <h2>Why the size of a side is part of its strength</h2>
 * A Manhunt is not two equal teams — it is usually one Runner against four Hunters, and the Hunters
 * win most of those whatever anybody's rating is. A side's strength is therefore its average rating
 * plus {@code 400·log10(size)}: four average Hunters count as one player about 240 points better,
 * which is how often four of anybody beat one in practice. Without it every Hunter would gain rating
 * just for being on the bigger side, and every Runner would bleed it.
 */
public final class Rating {

    /** Where everybody starts. */
    public static final double START = 1000;
    /** How far one hunt moves a rating at most. */
    public static final double K = 32;

    private Rating() {
    }

    /** A side's strength: mean rating plus the weight of its numbers. */
    static double strength(Collection<Double> ratings) {
        if (ratings.isEmpty()) {
            return 0;
        }
        double mean = ratings.stream().mapToDouble(Double::doubleValue).average().orElse(START);
        return mean + 400 * Math.log10(ratings.size());
    }

    /** The Runners' chance to win, between 0 and 1. */
    public static double runnersExpected(Collection<Double> runners, Collection<Double> hunters) {
        return 1 / (1 + Math.pow(10, (strength(hunters) - strength(runners)) / 400));
    }

    /**
     * Everybody's new rating after a hunt that one side won. Every Runner moves by the same amount and
     * every Hunter by its opposite: the game was played side against side.
     */
    public static Map<UUID, Double> afterHunt(Map<UUID, Double> runners, Map<UUID, Double> hunters,
                                              boolean runnersWon) {
        double expected = runnersExpected(runners.values(), hunters.values());
        double change = K * ((runnersWon ? 1 : 0) - expected);
        Map<UUID, Double> after = new LinkedHashMap<>();
        runners.forEach((id, rating) -> after.put(id, rating + change));
        hunters.forEach((id, rating) -> after.put(id, rating - change));
        return after;
    }
}
