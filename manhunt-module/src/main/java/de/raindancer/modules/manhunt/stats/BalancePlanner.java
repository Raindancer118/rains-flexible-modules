package de.raindancer.modules.manhunt.stats;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Splitting a lobby into Runners and Hunters so the hunt is as close to a coin toss as the ratings
 * allow — see {@link Rating} for what "close" means with sides of different sizes.
 *
 * <h2>Exact where it can be, greedy where it cannot</h2>
 * Every possible set of Runners is tried while there are few enough of them — a lobby of twenty with
 * up to three Runners is about 1 500 splits — and a larger crowd falls back to building the Runner
 * side one best player at a time, then swapping single players while that brings the odds closer.
 */
public final class BalancePlanner {

    /** How many splits are tried exhaustively for one Runner count before the greedy search takes over. */
    static final int EXHAUSTIVE_LIMIT = 20_000;

    /** A split, and the Runners' chance with it. */
    public record Plan(Set<UUID> runners, Set<UUID> hunters, double runnersExpected) {
        double distance() {
            return Math.abs(runnersExpected - 0.5);
        }
    }

    private BalancePlanner() {
    }

    /**
     * @param ratings    everybody to split, with their rating
     * @param maxRunners the most Runners wanted; 0 lets it choose, up to a third of the lobby
     * @return the fairest split, or an empty one for fewer than two players
     */
    public static Plan balance(Map<UUID, Double> ratings, int maxRunners) {
        List<UUID> players = new ArrayList<>(ratings.keySet());
        players.sort(Comparator.comparing((UUID id) -> -ratings.get(id)).thenComparing(UUID::toString));
        int n = players.size();
        if (n < 2) {
            return new Plan(Set.of(), Set.copyOf(players), 0);
        }
        int most = maxRunners > 0 ? Math.min(maxRunners, n - 1) : Math.max(1, (int) Math.ceil(n / 3.0));
        Plan best = null;
        for (int k = 1; k <= most; k++) {
            Plan candidate = combinations(n, k) <= EXHAUSTIVE_LIMIT
                    ? exhaustive(players, ratings, k)
                    : greedy(players, ratings, k);
            if (best == null || candidate.distance() < best.distance() - 1e-12) {
                best = candidate;
            }
        }
        return best;
    }

    /** {@code count} Runners drawn at random — never everybody, so somebody is left to chase. */
    public static Set<UUID> random(List<UUID> present, int count, Random random) {
        List<UUID> pool = new ArrayList<>(present);
        java.util.Collections.shuffle(pool, random);
        int take = Math.max(0, Math.min(count, pool.size() - 1));
        return Set.copyOf(pool.subList(0, take));
    }

    private static long combinations(int n, int k) {
        long result = 1;
        for (int i = 1; i <= k; i++) {
            result = result * (n - k + i) / i;
            if (result > EXHAUSTIVE_LIMIT) {
                return result;
            }
        }
        return result;
    }

    private static Plan exhaustive(List<UUID> players, Map<UUID, Double> ratings, int k) {
        Plan[] best = {null};
        choose(players, ratings, k, 0, new ArrayList<>(), best);
        return best[0];
    }

    private static void choose(List<UUID> players, Map<UUID, Double> ratings, int k, int from,
                               List<UUID> picked, Plan[] best) {
        if (picked.size() == k) {
            Plan plan = plan(players, ratings, new LinkedHashSet<>(picked));
            if (best[0] == null || plan.distance() < best[0].distance() - 1e-12) {
                best[0] = plan;
            }
            return;
        }
        for (int i = from; i <= players.size() - (k - picked.size()); i++) {
            picked.add(players.get(i));
            choose(players, ratings, k, i + 1, picked, best);
            picked.removeLast();
        }
    }

    private static Plan greedy(List<UUID> players, Map<UUID, Double> ratings, int k) {
        Set<UUID> runners = new LinkedHashSet<>(players.subList(0, k));
        Plan current = plan(players, ratings, runners);
        boolean improved = true;
        while (improved) {
            improved = false;
            for (UUID in : List.copyOf(runners)) {
                for (UUID out : players) {
                    if (runners.contains(out)) {
                        continue;
                    }
                    Set<UUID> swapped = new LinkedHashSet<>(runners);
                    swapped.remove(in);
                    swapped.add(out);
                    Plan tried = plan(players, ratings, swapped);
                    if (tried.distance() < current.distance() - 1e-9) {
                        runners = swapped;
                        current = tried;
                        improved = true;
                        break;
                    }
                }
                if (improved) {
                    break;
                }
            }
        }
        return current;
    }

    private static Plan plan(List<UUID> players, Map<UUID, Double> ratings, Set<UUID> runners) {
        Set<UUID> hunters = new LinkedHashSet<>(players);
        hunters.removeAll(runners);
        double expected = Rating.runnersExpected(
                runners.stream().map(ratings::get).toList(), hunters.stream().map(ratings::get).toList());
        return new Plan(Set.copyOf(runners), Set.copyOf(hunters), expected);
    }
}
