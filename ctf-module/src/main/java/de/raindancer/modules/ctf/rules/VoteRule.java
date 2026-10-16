package de.raindancer.modules.ctf.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Counting lobby votes, and what a ballot offers. */
public final class VoteRule implements ICtfRule {

    public String winner(List<String> options, Map<String, Integer> counts, Random random) {
        int most = options.stream().mapToInt(option -> counts.getOrDefault(option, 0)).max().orElse(0);
        List<String> top = options.stream().filter(option -> counts.getOrDefault(option, 0) == most).toList();
        return top.get(random.nextInt(top.size()));
    }

    /** Up to {@code size} of {@code all}, at random, leaving out {@code last} while there are others enough. */
    public List<String> ballot(List<String> all, int size, String last, Random random) {
        List<String> pool = new ArrayList<>(all);
        if (last != null && pool.size() > size) {
            pool.remove(last);
        }
        Collections.shuffle(pool, random);
        return List.copyOf(pool.subList(0, Math.min(size, pool.size())));
    }
}
