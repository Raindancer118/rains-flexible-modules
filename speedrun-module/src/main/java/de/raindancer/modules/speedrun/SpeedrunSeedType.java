package de.raindancer.modules.speedrun;

/**
 * Whether a run's world was a seed nobody knew, or one somebody could have practised — the line
 * every speedrun leaderboard draws, and the one this module keeps its leaderboards apart by.
 */
public enum SpeedrunSeedType {
    /** Generated fresh: nobody could have seen this map before the clock started. */
    RANDOM,
    /** Chosen, from a pool, or played before: a map that can be learned. */
    SET;

    public String label() {
        return this == RANDOM ? "Random seed" : "Set seed";
    }
}
