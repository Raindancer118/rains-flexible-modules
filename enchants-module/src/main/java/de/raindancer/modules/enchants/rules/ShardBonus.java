package de.raindancer.modules.enchants.rules;

import java.util.random.RandomGenerator;

/** Every shard drop, 30 to 50 % more: what was rolled, times a factor in that range, the fraction rounded up by chance. */
public final class ShardBonus {

    private static final double LEAST = 1.3;
    private static final double MOST = 1.5;

    private ShardBonus() {
    }

    public static int apply(int base, RandomGenerator random) {
        double more = base * (LEAST + random.nextDouble() * (MOST - LEAST));
        int whole = (int) Math.floor(more);
        // A single shard cannot become 1.4 shards: it becomes two that often.
        return whole + (random.nextDouble() < more - whole ? 1 : 0);
    }
}
