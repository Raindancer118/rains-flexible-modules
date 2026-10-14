package de.raindancer.modules.enchants.rules;

import org.bukkit.entity.EntityType;

import java.util.random.RandomGenerator;

/**
 * The shard world's warden: now and then a monster coming by itself under the open sky comes as a warden instead,
 * of whichever kind it rolls — far rarer than anything else out there, and worth five of its kind's shards.
 */
public final class CrystalWarden {

    public static final int SHARD_FACTOR = 5;

    private CrystalWarden() {
    }

    /** Whether this monster, coming by itself, comes as a warden instead — {@code perMille} in a thousand. */
    public static boolean rises(RandomGenerator random, int perMille, EntityType type, boolean openSky) {
        return openSky && perMille > 0 && type != EntityType.WARDEN && random.nextInt(1000) < perMille;
    }

    /** A warden's shards, from what its kind would drop. */
    public static int shards(int kindShards) {
        return kindShards * SHARD_FACTOR;
    }
}
