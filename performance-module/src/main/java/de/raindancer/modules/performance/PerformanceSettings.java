package de.raindancer.modules.performance;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Range;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import de.raindancer.modules.performance.rules.FindingRule;
import org.bukkit.Material;

/** What an owner decides about farms and the lag watch. The schema of the file and of the /settings page. */
@Settings(id = "performance", topics = {
        @Topic(path = "lag", title = "Lag watch", icon = Material.CLOCK),
        @Topic(path = "farms", title = "Farm limits", icon = Material.EGG),
})
public record PerformanceSettings(

        @In("lag") @Title("Watch the tick")
        @Describe("Whether slow ticks are noticed, their cause looked for, and staff told with a report.")
        @Key("watch")
        boolean watch,

        @In("lag") @Title("Strained from (ms)") @Range(min = 20, max = 50)
        @Describe("An average tick this slow leaves no headroom. Shown in /perf; not reported.")
        @Key("strained-ms")
        int strainedMs,

        @In("lag") @Title("A spike from (ms)") @Range(min = 60, max = 5000)
        @Describe("A single tick this slow is reported as a spike, with what the server was doing during it.")
        @Key("spike-ms")
        int spikeMs,

        @In("lag") @Title("Sample every (ms)") @Range(min = 0, max = 200)
        @Describe("How often the server thread is looked at to find what it is busy with. Each look costs a few "
                + "microseconds. Zero switches it off; reports then only count what lies where.")
        @Key("sample-every-ms")
        int sampleEveryMs,

        @In("lag") @Title("Quiet for (minutes)") @Range(min = 1, max = 240)
        @Describe("After a report, the same lag is not reported again for this long — unless it gets worse or "
                + "something new is found.")
        @Key("quiet-minutes")
        int quietMinutes,

        @In("lag") @Title("Lowest simulation distance") @Range(min = 2, max = 32)
        @Describe("A report never suggests a simulation distance below this.")
        @Key("lowest-simulation-distance")
        int lowestSimulationDistance,

        @In("lag") @Title("Name a plugin from (%)") @Range(min = 5, max = 100)
        @Describe("A plugin with this share of the busy time is named in a report.")
        @Key("suspect-percent")
        int suspectPercent,

        @In("lag") @Title("Items in a chunk") @Range(min = 10, max = 10000)
        @Describe("This many dropped items and experience orbs in one chunk are a pile worth reporting.")
        @Key("items-per-chunk")
        int itemsPerChunk,

        @In("lag") @Title("Animals of one kind in a chunk") @Range(min = 10, max = 10000)
        @Describe("This many animals of one kind in one chunk are a crowd worth reporting.")
        @Key("animals-per-chunk")
        int animalsPerChunk,

        @In("lag") @Title("Monsters in a chunk") @Range(min = 10, max = 10000)
        @Describe("This many hostile mobs in one chunk are a crowd worth reporting — usually a mob farm.")
        @Key("monsters-per-chunk")
        int monstersPerChunk,

        @In("lag") @Title("Villagers in a chunk") @Range(min = 5, max = 10000)
        @Describe("This many villagers in one chunk are reported. Villagers are never removed by a fix.")
        @Key("villagers-per-chunk")
        int villagersPerChunk,

        @In("lag") @Title("Block entities of one kind in a chunk") @Range(min = 10, max = 100000)
        @Describe("This many hoppers, furnaces or the like of one kind in one chunk are reported.")
        @Key("block-entities-per-chunk")
        int blockEntitiesPerChunk,

        @In("lag") @Title("Entities in a chunk") @Range(min = 20, max = 100000)
        @Describe("This many entities of any kind in one chunk are reported when nothing more specific is.")
        @Key("entities-per-chunk")
        int entitiesPerChunk,

        @In("lag") @Title("Reports kept") @Range(min = 1, max = 1000)
        @Describe("How many reports stay in plugins/<this plugin>/reports; older ones are removed.")
        @Key("reports-kept")
        int reportsKept,

        @In("farms") @Title("Limit farms")
        @Describe("Whether breeding, thrown eggs and dispensed eggs stop making new animals where "
                + "there are already too many. Animals that exist are never removed by this.")
        @Key("enabled")
        boolean enabled,

        @In("farms") @Title("Counted within") @Range(min = 4, max = 64)
        @Describe("Blocks around a new animal in which the others are counted. A farm across a chunk "
                + "border is still one farm.")
        @Key("radius")
        int radius,

        @In("farms") @Title("Most of one kind") @Range(min = 0, max = 1000)
        @Describe("How many animals of the same kind may be that close before no more are bred or "
                + "hatched. Zero means no limit.")
        @Key("most-of-one-kind")
        int mostOfOneKind,

        @In("farms") @Title("Most animals in all") @Range(min = 0, max = 2000)
        @Describe("How many breedable animals of any kind may be that close together. Zero means no limit.")
        @Key("most-animals")
        int mostAnimals,

        @In("farms") @Title("Tell players nearby")
        @Describe("Whether players near a farm that is full are told, in the action bar and at most "
                + "once a minute, why nothing more is born.")
        @Key("tell-players")
        boolean tellPlayers) {

    public static final PerformanceSettings DEFAULTS = new PerformanceSettings(true, 45, 250, 20, 10, 6, 25,
            150, 80, 60, 40, 150, 250, 50, true, 16, 50, 150, true);

    /** The defaults with other farm limits — what a test of the farm limit varies. */
    public static PerformanceSettings farms(boolean enabled, int radius, int mostOfOneKind, int mostAnimals, boolean tellPlayers) {
        PerformanceSettings d = DEFAULTS;
        return new PerformanceSettings(d.watch, d.strainedMs, d.spikeMs, d.sampleEveryMs, d.quietMinutes,
                d.lowestSimulationDistance, d.suspectPercent, d.itemsPerChunk, d.animalsPerChunk, d.monstersPerChunk,
                d.villagersPerChunk, d.blockEntitiesPerChunk, d.entitiesPerChunk, d.reportsKept,
                enabled, radius, mostOfOneKind, mostAnimals, tellPlayers);
    }

    public FindingRule.Limits limits() {
        return new FindingRule.Limits(itemsPerChunk, animalsPerChunk, monstersPerChunk, villagersPerChunk,
                blockEntitiesPerChunk, entitiesPerChunk);
    }

    /** What thinning keeps: the farm limit when it is on. */
    public int farmLimit() {
        return enabled ? mostOfOneKind : 0;
    }
}
