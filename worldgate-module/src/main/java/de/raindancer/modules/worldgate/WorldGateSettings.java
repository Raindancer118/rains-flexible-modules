package de.raindancer.modules.worldgate;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import org.bukkit.Material;

/**
 * The three world names this module cares about.
 *
 * <p>The record <em>is</em> the schema: the file, its comments and the {@code /settings} screens all
 * come from it. There is exactly one thing owners configure here — which world is which — because
 * whether a dimension is currently locked is not a preference at all; it is live, admin-toggled state,
 * which is why it lives in {@link de.raindancer.modules.worldgate.store.GateStateStore} instead, the
 * same split {@code RtpSettings} and {@code RtpLocationStorage} already draw.
 */
@Settings(id = "worldgate", topics = {
        @Topic(path = "worldgate", title = "World Gate", icon = Material.NETHER_PORTAL),
})
public record WorldGateSettings(

        @In("worldgate") @Title("The Nether's world name")
        @Describe("Which world is the Nether this module manages. Only worth changing on a server "
                + "that has renamed its worlds, or that runs more than one nether-like world and "
                + "wants a specific one gated.")
        @Key("nether-world")
        String netherWorld,

        @In("worldgate") @Title("The End's world name")
        @Describe("Which world is the End this module manages.")
        @Key("end-world")
        String endWorld,

        @In("worldgate") @Title("The overworld's world name")
        @Describe("Where an evacuated player lands. A player's own respawn point is used first, but "
                + "only when it is actually in this world — a bed set in the Nether would otherwise "
                + "send them right back into the dimension they were just pulled out of.")
        @Key("overworld-world")
        String overworldWorld) {

    public WorldGateSettings {
        netherWorld = blankToDefault(netherWorld, "world_nether");
        endWorld = blankToDefault(endWorld, "world_the_end");
        overworldWorld = blankToDefault(overworldWorld, "world");
    }

    public static final WorldGateSettings DEFAULTS =
            new WorldGateSettings("world_nether", "world_the_end", "world");

    // A blank or missing name is not a world anybody could mean, and defaulting it here — rather than
    // leaving an empty string that fails every world lookup silently — keeps a half-edited config.yml
    // from turning into "the Nether is nowhere" instead of "the Nether is world_nether".
    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    // ------------------------------------------------------------------ one component at a time

    public WorldGateSettings withNetherWorld(String value) {
        return new WorldGateSettings(value, endWorld, overworldWorld);
    }

    public WorldGateSettings withEndWorld(String value) {
        return new WorldGateSettings(netherWorld, value, overworldWorld);
    }

    public WorldGateSettings withOverworldWorld(String value) {
        return new WorldGateSettings(netherWorld, endWorld, value);
    }
}
