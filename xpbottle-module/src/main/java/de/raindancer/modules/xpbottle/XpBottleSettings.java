package de.raindancer.modules.xpbottle;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Range;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import org.bukkit.Material;

/**
 * What an owner can decide about bottling experience.
 *
 * <p>The record <em>is</em> the schema: the file, its comments, its validation and the
 * {@code /settings} screens all come from it, so there is nothing to keep in step. Every component
 * has a {@code with…} rather than a positional constructor being the way to change one — seven
 * {@code int}s in a row is a mis-ordering waiting to happen.
 *
 * <h2>Why the tiers are a base plus a step</h2>
 * Rather than a list of tiers with a capacity each. A list is what an owner asks for right up until
 * they add a fourth tier and find that only three have numbers; a base and a step always answer for
 * every tier there is, including one added by raising {@link #highestTier} alone.
 */
@Settings(id = "xpbottle", topics = {
        @Topic(path = "xpbottle", title = "XP bottles", icon = Material.EXPERIENCE_BOTTLE),
        @Topic(path = "xpbottle/plain", title = "Plain glass bottles", icon = Material.GLASS_BOTTLE),
        @Topic(path = "xpbottle/siphon", title = "Siphon bottles", icon = Material.DRAGON_BREATH),
})
public record XpBottleSettings(

        @In("xpbottle/plain") @Title("Plain bottles hold experience")
        @Describe("Whether an ordinary glass bottle can be right-clicked to draw experience out of "
                + "whoever is holding it. Off leaves glass bottles entirely vanilla, and only the "
                + "siphon bottles this module gives out do anything.")
        @Key("plain.enabled")
        boolean plainBottlesWork,

        @In("xpbottle/plain") @Title("A plain bottle holds") @Range(min = 1, max = 100000)
        @Describe("Experience points. Points, not levels: a level is worth a different number of "
                + "points depending on which level it is, so anything measured in levels gives back "
                + "a different amount than it took. Roughly a level and a half at level 15.")
        @Key("plain.capacity")
        int plainCapacity,

        @In("xpbottle/siphon") @Title("A tier I siphon bottle holds") @Range(min = 1, max = 1000000)
        @Describe("Experience points in the lowest siphon tier.")
        @Key("siphon.capacity-base")
        int siphonCapacityBase,

        @In("xpbottle/siphon") @Title("Each tier above I holds this much more")
        @Range(min = 0, max = 1000000)
        @Describe("Experience points added per tier, so tier III holds the base plus twice this. "
                + "Zero makes every tier hold the same and leaves the reach as the only difference.")
        @Key("siphon.capacity-per-tier")
        int siphonCapacityPerTier,

        @In("xpbottle/siphon") @Title("A tier I siphon bottle reaches") @Range(min = 1, max = 32)
        @Describe("Blocks. How far a held-down siphon bottle pulls loose experience orbs in from. "
                + "Also how much of the world is searched every fifth of a tick-batch, so a large "
                + "number is work for everybody near whoever is holding it, not only for them.")
        @Key("siphon.reach-base")
        int siphonReachBase,

        @In("xpbottle/siphon") @Title("Each tier above I reaches this much further")
        @Range(min = 0, max = 16)
        @Describe("Blocks added per tier. The reach is capped at 32 blocks whatever the sum comes "
                + "to — past that a bottle empties a room somebody cannot see into.")
        @Key("siphon.reach-per-tier")
        int siphonReachPerTier,

        @In("xpbottle/siphon") @Title("Highest siphon tier") @Range(min = 1, max = 10)
        @Describe("How many tiers of siphon bottle exist. Raising this creates the new tiers "
                + "immediately, with capacities and reaches that follow from the base and the step.")
        @Key("siphon.highest-tier")
        int highestTier,

        @In("xpbottle/siphon") @Title("A siphon draws this fast") @Range(min = 1, max = 20000)
        @Describe("Experience points per second while the bottle is held down. Deliberately not "
                + "instant: filling a bottle is something the holder watches happen and can stop "
                + "part-way through by letting go.")
        @Key("siphon.points-per-second")
        int siphonPointsPerSecond,

        @In("xpbottle") @Title("Wait between bottlings") @Range(min = 0, max = 3600)
        @Describe("Seconds before the same player may fill another bottle from their own "
                + "experience. Zero switches the wait off. The siphon is not covered by this — it "
                + "is already limited by how fast it draws.")
        @Key("fill-cooldown-seconds")
        int fillCooldownSeconds) {

    /** The reach nothing may exceed, whatever the base and the step add up to. */
    public static final int MOST_REACH = 32;

    public static final XpBottleSettings DEFAULTS =
            new XpBottleSettings(true, 100, 500, 500, 4, 2, 3, 200, 0);

    /** Tiers that exist, clamped into what the schema allows. */
    public int highestTierClamped() {
        return Math.max(1, Math.min(10, highestTier));
    }

    /**
     * What a bottle of this tier holds.
     *
     * @param level 0 for a plain glass bottle, 1 and up for a siphon
     */
    public int capacityFor(int level) {
        if (level <= 0) {
            return Math.max(1, plainCapacity);
        }
        int tier = Math.min(level, highestTierClamped());
        long capacity = (long) Math.max(1, siphonCapacityBase)
                + (long) (tier - 1) * Math.max(0, siphonCapacityPerTier);
        return (int) Math.min(Integer.MAX_VALUE, capacity);
    }

    /** How far a bottle of this tier reaches, in blocks. Zero for a plain bottle, which never does. */
    public int reachFor(int level) {
        if (level <= 0) {
            return 0;
        }
        int tier = Math.min(level, highestTierClamped());
        long reach = (long) Math.max(1, siphonReachBase)
                + (long) (tier - 1) * Math.max(0, siphonReachPerTier);
        return (int) Math.min(MOST_REACH, reach);
    }

    /**
     * The points a siphon may draw in one run of the timer.
     *
     * <p>Rounded up rather than down, so a slow setting still draws something rather than
     * silently drawing nothing per go and looking broken.
     *
     * @param periodTicks how often the timer runs
     */
    public int pointsPerTimerRun(long periodTicks) {
        long perSecond = Math.max(1, siphonPointsPerSecond);
        long ticks = Math.max(1, periodTicks);
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, (perSecond * ticks + 19) / 20));
    }

    public XpBottleSettings withPlainBottlesWork(boolean value) {
        return new XpBottleSettings(value, plainCapacity, siphonCapacityBase, siphonCapacityPerTier,
                siphonReachBase, siphonReachPerTier, highestTier, siphonPointsPerSecond,
                fillCooldownSeconds);
    }

    public XpBottleSettings withPlainCapacity(int value) {
        return new XpBottleSettings(plainBottlesWork, value, siphonCapacityBase,
                siphonCapacityPerTier, siphonReachBase, siphonReachPerTier, highestTier,
                siphonPointsPerSecond, fillCooldownSeconds);
    }

    public XpBottleSettings withSiphonCapacityBase(int value) {
        return new XpBottleSettings(plainBottlesWork, plainCapacity, value, siphonCapacityPerTier,
                siphonReachBase, siphonReachPerTier, highestTier, siphonPointsPerSecond,
                fillCooldownSeconds);
    }

    public XpBottleSettings withSiphonCapacityPerTier(int value) {
        return new XpBottleSettings(plainBottlesWork, plainCapacity, siphonCapacityBase, value,
                siphonReachBase, siphonReachPerTier, highestTier, siphonPointsPerSecond,
                fillCooldownSeconds);
    }

    public XpBottleSettings withSiphonReachBase(int value) {
        return new XpBottleSettings(plainBottlesWork, plainCapacity, siphonCapacityBase,
                siphonCapacityPerTier, value, siphonReachPerTier, highestTier,
                siphonPointsPerSecond, fillCooldownSeconds);
    }

    public XpBottleSettings withSiphonReachPerTier(int value) {
        return new XpBottleSettings(plainBottlesWork, plainCapacity, siphonCapacityBase,
                siphonCapacityPerTier, siphonReachBase, value, highestTier, siphonPointsPerSecond,
                fillCooldownSeconds);
    }

    public XpBottleSettings withHighestTier(int value) {
        return new XpBottleSettings(plainBottlesWork, plainCapacity, siphonCapacityBase,
                siphonCapacityPerTier, siphonReachBase, siphonReachPerTier, value,
                siphonPointsPerSecond, fillCooldownSeconds);
    }

    public XpBottleSettings withSiphonPointsPerSecond(int value) {
        return new XpBottleSettings(plainBottlesWork, plainCapacity, siphonCapacityBase,
                siphonCapacityPerTier, siphonReachBase, siphonReachPerTier, highestTier, value,
                fillCooldownSeconds);
    }

    public XpBottleSettings withFillCooldownSeconds(int value) {
        return new XpBottleSettings(plainBottlesWork, plainCapacity, siphonCapacityBase,
                siphonCapacityPerTier, siphonReachBase, siphonReachPerTier, highestTier,
                siphonPointsPerSecond, value);
    }
}
