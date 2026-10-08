package de.raindancer.modules.economy.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A horse race. Each horse has a fixed chance of winning, a bet on it pays {@code (1 − edge) / chance}, and
 * the winner is drawn by those chances before the race starts; the race itself is generated so that horse
 * crosses the line first, alone.
 */
public final class HorseRaceRule implements IEconomyRule {

    public static final List<String> NAMES = List.of("Thunder", "Biscuit", "Comet", "Shadow");
    private static final int[] WEIGHTS = {38, 28, 21, 13};
    public static final int TRACK = 40;

    public int horses() {
        return WEIGHTS.length;
    }

    public double chance(int horse) {
        int total = 0;
        for (int weight : WEIGHTS) {
            total += weight;
        }
        return WEIGHTS[horse] / (double) total;
    }

    public double pays(int horse, double edge) {
        return (1.0 - Math.max(0, Math.min(0.5, edge))) / chance(horse);
    }

    /** @param uniform a number in [0, 1) */
    public int winner(double uniform) {
        double left = uniform;
        for (int horse = 0; horse < WEIGHTS.length; horse++) {
            left -= chance(horse);
            if (left < 0) {
                return horse;
            }
        }
        return WEIGHTS.length - 1;
    }

    /**
     * Every horse's distance after each step, the winner reaching the line strictly first.
     *
     * @return per horse, the list of positions frame by frame (all the same length)
     */
    public List<List<Integer>> race(int winner, Random random) {
        List<List<Integer>> paths = new ArrayList<>();
        int[] finish = new int[WEIGHTS.length];
        for (int horse = 0; horse < WEIGHTS.length; horse++) {
            List<Integer> path = new ArrayList<>();
            int at = 0;
            while (at < TRACK) {
                at = Math.min(TRACK, at + 1 + random.nextInt(3));
                path.add(at);
            }
            paths.add(path);
            finish[horse] = path.size();
        }
        int fastest = 0;
        for (int horse = 1; horse < finish.length; horse++) {
            if (finish[horse] < finish[fastest]) {
                fastest = horse;
            }
        }
        List<Integer> swap = paths.get(winner);
        paths.set(winner, paths.get(fastest));
        paths.set(fastest, swap);
        int line = paths.get(winner).size();
        for (int horse = 0; horse < paths.size(); horse++) {
            List<Integer> path = paths.get(horse);
            if (horse != winner && path.size() <= line) {
                // Held back a stride before the line, so nobody ties the winner.
                path.add(path.size() - 1, path.get(Math.max(0, path.size() - 2)));
                while (path.size() <= line) {
                    path.add(path.size() - 1, path.get(Math.max(0, path.size() - 2)));
                }
            }
        }
        int frames = paths.stream().mapToInt(List::size).max().orElse(0);
        for (List<Integer> path : paths) {
            while (path.size() < frames) {
                path.add(TRACK);
            }
        }
        return paths;
    }

    @Override
    public String describe() {
        return "a horse race's odds, its winner, and the race that shows it";
    }
}
