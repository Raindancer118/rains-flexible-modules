package de.raindancer.modules.chained;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Range;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import de.raindancer.core.world.speedrun.conditions.DeathEndCondition;
import org.bukkit.Material;

/**
 * What an owner can decide about a chained-together speedrun.
 *
 * <p>The record <em>is</em> the schema: the file, its comments, its validation and the
 * {@code /settings} screens all come from it, so there is nothing to keep in step and no second list
 * to forget. {@link #DEFAULTS} is real Java rather than literals in a yaml, and every component has
 * a {@code with…} of its own — a positional constructor with ten components is a mis-ordering
 * waiting to happen, and two swapped values compile perfectly.
 */
@Settings(id = "chained", topics = {
        @Topic(path = "chained", title = "Chained", icon = Material.IRON_CHAIN),
        @Topic(path = "chained/limits", title = "How far apart", icon = Material.LEAD),
        @Topic(path = "chained/ending", title = "Ending a run", icon = Material.CLOCK),
        @Topic(path = "chained/resetting", title = "Resetting the map", icon = Material.TNT),
})
public record ChainedSettings(

        @In("chained/limits") @Title("Max distance apart") @Range(min = 1, max = 10_000)
        @Describe("Blocks. Moving further apart than this is simply blocked, like an invisible "
                + "wall — no damage, no teleport-pull. The move is refused and that is all.")
        @Key("max-distance")
        int maxDistanceBlocks,

        @In("chained/limits") @Title("Warn this far before the wall") @Range(min = 0, max = 1000)
        @Describe("Blocks of slack left before the wall at which a gentle warning is sent, so "
                + "somebody sees it coming rather than only ever hitting it. Zero switches the "
                + "early warning off; the wall itself still refuses the move either way.")
        @Key("warning-distance")
        int warningDistanceBlocks,

        @In("chained/limits") @Title("Wait between refusal messages") @Range(min = 0, max = 60)
        @Describe("Seconds between one 'you are chained' message and the next for the same "
                + "player, so walking into the wall repeatedly does not fill their chat.")
        @Key("warning-cooldown-seconds")
        int warningCooldownSeconds,

        @In("chained/ending") @Title("What ends a run")
        @Describe("An advancement, a death, or nothing automatic — ended by /chain stop instead.")
        @Key("end-condition")
        EndCondition endCondition,

        @In("chained/ending") @Title("The advancement to race for")
        @Describe("Only used when a run ends on an advancement. The vanilla dragon kill by "
                + "default, but any advancement key works.")
        @Key("advancement-key")
        String advancementKey,

        @In("chained/ending") @Title("Whose death ends it")
        @Describe("Only used when a run ends on a death: the first participant to die (a "
                + "hardcore race), or only once both have (co-op).")
        @Key("death-policy")
        DeathEndCondition.DeathPolicy deathPolicy,

        @In("chained/resetting") @Title("Reset the map before a run starts")
        @Describe("Whether starting a run throws the configured world away and makes it again "
                + "first, so every attempt begins from the same kind of map rather than one "
                + "already explored by the last attempt.")
        @Key("reset-on-start")
        boolean resetOnStart,

        @In("chained/resetting") @Title("Which world to reset")
        @Describe("The name of the world a run's reset regenerates. Only used when the setting "
                + "above is on, or when /chain reset is typed.")
        @Key("world-name")
        String worldName,

        @In("chained/resetting") @Title("Seed policy")
        @Describe("A fixed seed makes every attempt the same map; a random one makes each "
                + "attempt a fresh map.")
        @Key("seed-choice")
        SeedChoice seedChoice,

        @In("chained/resetting") @Title("The fixed seed")
        @Describe("Only used when the seed policy above is fixed.")
        @Key("seed-value")
        long seedValue) {

    /** What ends a run. */
    public enum EndCondition { ADVANCEMENT, DEATH, MANUAL }

    /** How a reset world's terrain is chosen. */
    public enum SeedChoice { FIXED, RANDOM }

    public static final ChainedSettings DEFAULTS = new ChainedSettings(
            32, 5, 5,
            EndCondition.ADVANCEMENT, "minecraft:end/kill_dragon", DeathEndCondition.DeathPolicy.ANY,
            false, "world", SeedChoice.RANDOM, 0L);

    // ------------------------------------------------------------------ read back safely

    public int maxDistance() {
        return Math.max(1, Math.min(10_000, maxDistanceBlocks));
    }

    public int warningDistance() {
        return Math.max(0, Math.min(1000, warningDistanceBlocks));
    }

    public int warningCooldown() {
        return Math.max(0, Math.min(60, warningCooldownSeconds));
    }

    // ------------------------------------------------------------------ one component at a time

    public ChainedSettings withMaxDistanceBlocks(int blocks) {
        return new ChainedSettings(blocks, warningDistanceBlocks, warningCooldownSeconds,
                endCondition, advancementKey, deathPolicy, resetOnStart, worldName, seedChoice,
                seedValue);
    }

    public ChainedSettings withWarningDistanceBlocks(int blocks) {
        return new ChainedSettings(maxDistanceBlocks, blocks, warningCooldownSeconds,
                endCondition, advancementKey, deathPolicy, resetOnStart, worldName, seedChoice,
                seedValue);
    }

    public ChainedSettings withWarningCooldownSeconds(int seconds) {
        return new ChainedSettings(maxDistanceBlocks, warningDistanceBlocks, seconds,
                endCondition, advancementKey, deathPolicy, resetOnStart, worldName, seedChoice,
                seedValue);
    }

    public ChainedSettings withEndCondition(EndCondition condition) {
        return new ChainedSettings(maxDistanceBlocks, warningDistanceBlocks, warningCooldownSeconds,
                condition, advancementKey, deathPolicy, resetOnStart, worldName, seedChoice,
                seedValue);
    }

    public ChainedSettings withAdvancementKey(String key) {
        return new ChainedSettings(maxDistanceBlocks, warningDistanceBlocks, warningCooldownSeconds,
                endCondition, key, deathPolicy, resetOnStart, worldName, seedChoice, seedValue);
    }

    public ChainedSettings withDeathPolicy(DeathEndCondition.DeathPolicy policy) {
        return new ChainedSettings(maxDistanceBlocks, warningDistanceBlocks, warningCooldownSeconds,
                endCondition, advancementKey, policy, resetOnStart, worldName, seedChoice, seedValue);
    }

    public ChainedSettings withResetOnStart(boolean reset) {
        return new ChainedSettings(maxDistanceBlocks, warningDistanceBlocks, warningCooldownSeconds,
                endCondition, advancementKey, deathPolicy, reset, worldName, seedChoice, seedValue);
    }

    public ChainedSettings withWorldName(String world) {
        return new ChainedSettings(maxDistanceBlocks, warningDistanceBlocks, warningCooldownSeconds,
                endCondition, advancementKey, deathPolicy, resetOnStart, world, seedChoice, seedValue);
    }

    public ChainedSettings withSeedChoice(SeedChoice choice) {
        return new ChainedSettings(maxDistanceBlocks, warningDistanceBlocks, warningCooldownSeconds,
                endCondition, advancementKey, deathPolicy, resetOnStart, worldName, choice, seedValue);
    }

    public ChainedSettings withSeedValue(long seed) {
        return new ChainedSettings(maxDistanceBlocks, warningDistanceBlocks, warningCooldownSeconds,
                endCondition, advancementKey, deathPolicy, resetOnStart, worldName, seedChoice, seed);
    }
}
