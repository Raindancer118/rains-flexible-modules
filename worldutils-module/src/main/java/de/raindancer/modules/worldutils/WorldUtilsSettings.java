package de.raindancer.modules.worldutils;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Range;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import org.bukkit.Material;

/**
 * What an owner decides about moving between worlds.
 *
 * <p>The record <em>is</em> the schema: the file, its comments and the {@code /settings} screens all
 * come from it. Which worlds exist is not a setting — that is live state {@code /worlds} changes, kept
 * in {@code worlds.yml}.
 */
@Settings(id = "worldutils", topics = {
        @Topic(path = "worldutils", title = "World Utils", icon = Material.GRASS_BLOCK),
})
public record WorldUtilsSettings(

        @In("worldutils") @Title("Go back to where you left a world")
        @Describe("With this on, /w puts somebody back where they last stood in that world. Off, "
                + "everybody arrives at the world's spawn.")
        @Key("remember-last-position")
        boolean rememberLastPosition,

        @In("worldutils") @Title("Look for safe ground on arrival")
        @Describe("Moves an arrival to the nearest spot somebody can stand on without suffocating, "
                + "falling or burning. Off, /w and /dim land on the exact coordinates.")
        @Key("safe-arrival")
        boolean safeArrival,

        @In("worldutils") @Title("How far to look for safe ground") @Range(min = 1, max = 64)
        @Describe("Blocks sideways. Also how much of the world is loaded to look, so keep it modest.")
        @Key("search-radius")
        int searchRadius,

        @In("worldutils") @Title("Link the portals of worlds made here")
        @Describe("A world made at runtime has no nether or end of its own, so its portals lead to the "
                + "server's. With this on, a portal in a world made with /worlds leads to its own "
                + "_nether and _the_end when they exist.")
        @Key("link-portals")
        boolean linkPortals) {

    public static final WorldUtilsSettings DEFAULTS = new WorldUtilsSettings(true, true, 16, true);

    /** The radius, kept to what the schema allows even when the file says otherwise. */
    public int radius() {
        return Math.max(1, Math.min(64, searchRadius));
    }

    public WorldUtilsSettings withRememberLastPosition(boolean value) {
        return new WorldUtilsSettings(value, safeArrival, searchRadius, linkPortals);
    }

    public WorldUtilsSettings withSafeArrival(boolean value) {
        return new WorldUtilsSettings(rememberLastPosition, value, searchRadius, linkPortals);
    }

    public WorldUtilsSettings withSearchRadius(int value) {
        return new WorldUtilsSettings(rememberLastPosition, safeArrival, value, linkPortals);
    }

    public WorldUtilsSettings withLinkPortals(boolean value) {
        return new WorldUtilsSettings(rememberLastPosition, safeArrival, searchRadius, value);
    }
}
