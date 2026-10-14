package de.raindancer.modules.enchants.rules;

import java.util.Locale;
import java.util.random.RandomGenerator;

/**
 * The kinds of monster in the shard world, weakest first: how often each spawns, how much tougher it is, and
 * how many shards it drops — the harder, the more, and the likelier a rare enchantment's shard.
 */
public enum ShardTier {
    SHADOWED("<gray>Shadowed <name>", 60, 1.0, 0, 1.0, 0, 1, 1, 1),
    SHARDBORN("<light_purple>Shardborn <name>", 28, 1.5, 2, 1.05, 2, 2, 2, 1),
    CRYSTAL("<aqua>Crystal <name>", 10, 2.5, 4, 1.15, 6, 3, 4, 2),
    TYRANT("<gold><bold>Shard Tyrant</bold> <name>", 2, 5.0, 8, 1.2, 12, 6, 8, 4);

    private final String title;
    private final int weight;
    private final double healthFactor;
    private final double extraDamage;
    private final double speedFactor;
    private final double armour;
    private final int fewestShards;
    private final int mostShards;
    private final int rareFactor;

    ShardTier(String title, int weight, double healthFactor, double extraDamage, double speedFactor, double armour,
              int fewestShards, int mostShards, int rareFactor) {
        this.title = title;
        this.weight = weight;
        this.healthFactor = healthFactor;
        this.extraDamage = extraDamage;
        this.speedFactor = speedFactor;
        this.armour = armour;
        this.fewestShards = fewestShards;
        this.mostShards = mostShards;
        this.rareFactor = rareFactor;
    }

    public static ShardTier roll(RandomGenerator random) {
        int total = 0;
        for (ShardTier tier : values()) {
            total += tier.weight;
        }
        int roll = random.nextInt(total);
        for (ShardTier tier : values()) {
            roll -= tier.weight;
            if (roll < 0) {
                return tier;
            }
        }
        return SHADOWED;
    }

    public static ShardTier of(String key) {
        if (key != null) {
            for (ShardTier tier : values()) {
                if (tier.key().equals(key)) {
                    return tier;
                }
            }
        }
        return SHADOWED;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** MiniMessage of the whole name, in the tier's colour; {@code <name>} is the creature's own, as the game words it. */
    public String title() {
        return title;
    }

    public int shards(RandomGenerator random) {
        return fewestShards + random.nextInt(mostShards - fewestShards + 1);
    }

    public double healthFactor() {
        return healthFactor;
    }

    /** What all its damage is multiplied by — melee, arrows, explosions: 30 % more each kind up. */
    public double damageFactor() {
        return Math.pow(1.3, ordinal());
    }

    public double extraDamage() {
        return extraDamage;
    }

    /** Its speed, for a baby zombie or anything else: a baby zombie tyrant is 20 % faster still. */
    public double speedFactor(boolean babyZombie) {
        return this == TYRANT && babyZombie ? speedFactor() * 1.2 : speedFactor();
    }

    public double speedFactor() {
        return speedFactor;
    }

    public double armour() {
        return armour;
    }

    public int fewestShards() {
        return fewestShards;
    }

    public int mostShards() {
        return mostShards;
    }

    /** How much likelier this kind makes a rare enchantment's shard. */
    public int rareFactor() {
        return rareFactor;
    }

    /** The two strongest are named for everybody to see from afar. */
    public boolean nameShown() {
        return ordinal() >= CRYSTAL.ordinal();
    }
}
