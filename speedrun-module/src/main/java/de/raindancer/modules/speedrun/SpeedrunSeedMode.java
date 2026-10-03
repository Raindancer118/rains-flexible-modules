package de.raindancer.modules.speedrun;

/** Where the next run's world gets its seed from — {@code seed-mode}. */
public enum SpeedrunSeedMode {
    /** A new random seed for every world. */
    RANDOM,
    /** Always {@code seed}. */
    FIXED,
    /** One of {@code seed-pool}, picked at random each reset. */
    POOL
}
