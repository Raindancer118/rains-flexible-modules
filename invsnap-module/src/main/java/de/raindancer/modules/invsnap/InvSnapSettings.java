package de.raindancer.modules.invsnap;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Range;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import org.bukkit.Material;

import java.util.List;

import java.time.Duration;

/**
 * What an owner can decide about inventory snapshots.
 *
 * <p>The record <em>is</em> the schema — see {@code MannequinSettings} for why, and why every
 * component has a {@code with…} rather than a positional constructor being the way to change one.
 */
@Settings(id = "invsnap", topics = {
        @Topic(path = "invsnap", title = "Inventory snapshots", icon = Material.CHEST),
})
public record InvSnapSettings(

        @In("invsnap") @Title("Snapshot interval") @Range(min = 30, max = 86400)
        @Describe("Seconds between one automatic snapshot of every online player's inventory and "
                + "the next. Five minutes by default.")
        @Key("snapshot.interval-seconds")
        int snapshotIntervalSeconds,

        @In("invsnap") @Title("Snapshots kept per player") @Range(min = 1, max = 500)
        @Describe("How many of a player's most recent snapshots are kept. The oldest is dropped "
                + "once a new one would push the count past this.")
        @Key("snapshot.retention-count")
        int retentionCount,

        @In("invsnap/insurance") @Title("Death insurance")
        @Describe("On: players can opt in with /insurance on. When an insured player dies they pay a "
                + "premium and keep their inventory; if they cannot pay, the death is a normal one. "
                + "Needs an economy once any price below is set.")
        @Key("insurance.enabled")
        boolean insuranceEnabled,

        @In("invsnap/insurance") @Title("Premium, percent of inventory value") @Range(min = 0, max = 100)
        @Describe("This percent of what the inventory is worth at death (armour and off hand "
                + "included; items without a shop price count nothing). 0 for none.")
        @Key("insurance.price-percent")
        double insurancePricePercent,

        @In("invsnap/insurance") @Title("Premium, flat")
        @Describe("Added to the percentage: money, such as 50 or 1.5k. 0 for none.")
        @Key("insurance.price-flat")
        String insurancePriceFlat,

        @In("invsnap/insurance") @Title("Premium, most")
        @Describe("The most one death can cost, such as 500. 0 for no cap.")
        @Key("insurance.most")
        String insuranceMost,

        @In("invsnap/insurance") @Title("Keep experience too")
        @Describe("On: an insured death also keeps the level. Off: only items are insured.")
        @Key("insurance.keep-xp")
        boolean insuranceKeepXp,

        @In("invsnap/insurance") @Title("Insured worlds")
        @Describe("World names where insurance applies, comma separated. Empty means every world. "
                + "Leave out worlds where another plugin manages inventories.")
        @Key("insurance.worlds")
        List<String> insuranceWorlds) {

    /** Snapshot settings with insurance at its shipped (off) default. */
    public InvSnapSettings(int snapshotIntervalSeconds, int retentionCount) {
        this(snapshotIntervalSeconds, retentionCount, false, 0, "0", "0", false, List.of());
    }

    public static final InvSnapSettings DEFAULTS =
            new InvSnapSettings(300, 24, false, 0, "0", "0", false, List.of());

    /** {@link #snapshotIntervalSeconds}, clamped and widened into a real {@link Duration}. */
    public Duration snapshotInterval() {
        return Duration.ofSeconds(Math.max(30, Math.min(86_400, snapshotIntervalSeconds)));
    }

    /** {@link #retentionCount}, clamped. */
    public int retentionCountClamped() {
        return Math.max(1, Math.min(500, retentionCount));
    }

    public InvSnapSettings withSnapshotIntervalSeconds(int seconds) {
        return new InvSnapSettings(seconds, retentionCount, insuranceEnabled, insurancePricePercent,
                insurancePriceFlat, insuranceMost, insuranceKeepXp, insuranceWorlds);
    }

    public InvSnapSettings withRetentionCount(int count) {
        return new InvSnapSettings(snapshotIntervalSeconds, count, insuranceEnabled,
                insurancePricePercent, insurancePriceFlat, insuranceMost, insuranceKeepXp, insuranceWorlds);
    }

    public InvSnapSettings withInsuranceEnabled(boolean enabled) {
        return new InvSnapSettings(snapshotIntervalSeconds, retentionCount, enabled,
                insurancePricePercent, insurancePriceFlat, insuranceMost, insuranceKeepXp, insuranceWorlds);
    }

    public InvSnapSettings withInsurancePrice(double percent, String flat, String most) {
        return new InvSnapSettings(snapshotIntervalSeconds, retentionCount, insuranceEnabled,
                percent, flat, most, insuranceKeepXp, insuranceWorlds);
    }

    public InvSnapSettings withInsuranceKeepXp(boolean keep) {
        return new InvSnapSettings(snapshotIntervalSeconds, retentionCount, insuranceEnabled,
                insurancePricePercent, insurancePriceFlat, insuranceMost, keep, insuranceWorlds);
    }

    public InvSnapSettings withInsuranceWorlds(List<String> worlds) {
        return new InvSnapSettings(snapshotIntervalSeconds, retentionCount, insuranceEnabled,
                insurancePricePercent, insurancePriceFlat, insuranceMost, insuranceKeepXp, worlds);
    }

    /** Whether insurance covers a death in this world; an empty list means every world. */
    public boolean insuresWorld(String world) {
        if (insuranceWorlds == null || insuranceWorlds.isEmpty()) {
            return true;
        }
        return insuranceWorlds.stream().anyMatch(name -> name.equalsIgnoreCase(world));
    }
}
