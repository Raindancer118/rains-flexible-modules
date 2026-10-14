package de.raindancer.modules.enchants.rules;

/** What the Shard Tyrants do beyond hitting hard: zombies call for help, skeletons fire like a minigun. */
public final class Tyrant {

    /** Reinforcements a zombie tyrant calls. */
    public static final int REINFORCEMENTS = 4;
    /** A burst: an arrow every {@link #SHOT_EVERY} ticks for this long. */
    public static final int BURST_TICKS = 48;
    public static final int SHOT_EVERY = 2;
    public static final int PAUSE_TICKS = 40;

    private Tyrant() {
    }

    /** Whether a kind has the tyrant's powers: the tyrant, and the overlord above it. */
    public static boolean powers(ShardTier tier) {
        return tier != null && tier.ordinal() >= ShardTier.TYRANT.ordinal();
    }

    /** How many a zombie tyrant calls: four, an overlord six. */
    public static int helpers(ShardTier tier) {
        return tier == ShardTier.OVERLORD ? 6 : REINFORCEMENTS;
    }

    /** The kind of those it calls: Shardborn for a tyrant, Crystal for an overlord. */
    public static ShardTier helperKind(ShardTier tier) {
        return tier == ShardTier.OVERLORD ? ShardTier.CRYSTAL : ShardTier.SHARDBORN;
    }

    /** Whether this hit — health {@code before} to {@code after} of {@code max} — is the one that calls for help. */
    public static boolean callsForHelp(double before, double after, double max, boolean calledAlready) {
        return !calledAlready && before >= max / 2 && after < max / 2;
    }

    /** Whether a skeleton tyrant fires on this tick of its fight. */
    public static boolean fires(int tick) {
        int inCycle = tick % (BURST_TICKS + PAUSE_TICKS);
        return inCycle < BURST_TICKS && inCycle % SHOT_EVERY == 0;
    }
}
