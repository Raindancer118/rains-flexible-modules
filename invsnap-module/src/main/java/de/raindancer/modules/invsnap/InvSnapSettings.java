package de.raindancer.modules.invsnap;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Range;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import org.bukkit.Material;

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
        int retentionCount) {

    public static final InvSnapSettings DEFAULTS = new InvSnapSettings(300, 24);

    /** {@link #snapshotIntervalSeconds}, clamped and widened into a real {@link Duration}. */
    public Duration snapshotInterval() {
        return Duration.ofSeconds(Math.max(30, Math.min(86_400, snapshotIntervalSeconds)));
    }

    /** {@link #retentionCount}, clamped. */
    public int retentionCountClamped() {
        return Math.max(1, Math.min(500, retentionCount));
    }

    public InvSnapSettings withSnapshotIntervalSeconds(int seconds) {
        return new InvSnapSettings(seconds, retentionCount);
    }

    public InvSnapSettings withRetentionCount(int count) {
        return new InvSnapSettings(snapshotIntervalSeconds, count);
    }
}
