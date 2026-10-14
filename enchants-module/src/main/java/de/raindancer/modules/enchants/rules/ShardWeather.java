package de.raindancer.modules.enchants.rules;

import java.util.random.RandomGenerator;

/** The shard world's weather: a roll every so often, a thunderstorm as often as the owner set. */
public final class ShardWeather {

    private ShardWeather() {
    }

    /** Whether the next stretch is a thunderstorm, {@code percent} of the time. */
    public static boolean thunder(RandomGenerator random, int percent) {
        return random.nextInt(100) < Math.max(0, Math.min(100, percent));
    }
}
