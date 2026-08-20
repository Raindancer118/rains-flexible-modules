package de.raindancer.modules.xaeromap;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Range;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import de.raindancer.modules.xaeromap.model.MapAudience;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;

import java.time.Duration;

/**
 * What an owner can decide about what the client's map is told.
 *
 * <p>The record <em>is</em> the schema, and every component has a {@code with…} rather than being
 * changed through the positional constructor — see {@code MannequinSettings} for why.
 */
@Settings(id = "xaeromap", topics = {
        @Topic(path = "xaeromap", title = "Xaero's Map support", icon = Material.FILLED_MAP),
        @Topic(path = "xaeromap.claims", title = "Claims on the map", icon = Material.GRASS_BLOCK),
})
public record XaeroMapSettings(

        @In("xaeromap") @Title("A map per world")
        @Describe("Tell Xaero's Minimap and World Map which world a player is in, so each world gets "
                + "its own map instead of every world drawing over one shared map. Off only if "
                + "another plugin on this server already sends that.")
        @Key("world-ids")
        boolean worldIds,

        @In("xaeromap.claims") @Title("Draw claims on the map")
        @Describe("Send this server's claims to Xaero's Minimap and World Map, which draw them as "
                + "coloured chunks. Needs a claims plugin; without one this does nothing.")
        @Key("claims.enabled")
        boolean claims,

        @In("xaeromap.claims") @Title("Whose claims are shown")
        @Describe("EVERYBODY draws every claim on the server. MINE_AND_SHARED draws only the ones a "
                + "player owns or is trusted on, for a server where where people live is worth "
                + "keeping quiet.")
        @Key("claims.shown-to")
        MapAudience shownTo,

        @In("xaeromap.claims") @Title("Least of a chunk that counts") @Range(min = 1, max = 100)
        @Describe("How much of a chunk a claim must cover before that chunk is drawn as claimed, in "
                + "percent. The map cannot draw anything smaller than a chunk, so a claim clipping "
                + "one corner still paints the whole of it. At 1 nothing is left out.")
        @Key("claims.chunk-coverage-percent")
        int chunkCoveragePercent,

        @In("xaeromap.claims") @Title("Refresh") @Range(min = 2, max = 300)
        @Describe("Seconds between checks for claims that have been made, resized, renamed or "
                + "deleted. Only the difference is sent, so a short interval is cheap.")
        @Key("claims.refresh-seconds")
        int refreshSeconds,

        @In("xaeromap.claims") @Title("Chunks sent per refresh") @Range(min = 16, max = 8192)
        @Describe("The most chunk changes one player is sent in one refresh. A budget rather than a "
                + "limit: whatever is left over goes out on the next one, so pasting in a thousand "
                + "claims at once cannot flood anybody off the server.")
        @Key("claims.chunks-per-refresh")
        int chunksPerRefresh,

        @In("xaeromap.claims") @Title("Your own claims")
        @Describe("The colour a player's own claims are drawn in on their own map.")
        @Key("claims.colour.own")
        NamedTextColor ownColour,

        @In("xaeromap.claims") @Title("Claims shared with you")
        @Describe("The colour of claims a player is trusted on. Everybody else's take a colour from "
                + "the owner, so two neighbours are never one blob.")
        @Key("claims.colour.shared")
        NamedTextColor sharedColour) {

    public static final XaeroMapSettings DEFAULTS = new XaeroMapSettings(
            true, true, MapAudience.EVERYBODY, 1, 5, 512,
            NamedTextColor.GREEN, NamedTextColor.AQUA);

    /** {@link #refreshSeconds}, clamped, as a real duration. */
    public Duration refresh() {
        return Duration.ofSeconds(Math.max(2, Math.min(300, refreshSeconds)));
    }

    /** {@link #chunkCoveragePercent}, clamped. */
    public int coveragePercentClamped() {
        return Math.max(1, Math.min(100, chunkCoveragePercent));
    }

    /** {@link #chunksPerRefresh}, clamped. */
    public int chunkBudget() {
        return Math.max(16, Math.min(8192, chunksPerRefresh));
    }

    /** Never null: a config that names a colour nobody has heard of falls back rather than throws. */
    public MapAudience audience() {
        return shownTo == null ? MapAudience.EVERYBODY : shownTo;
    }

    public XaeroMapSettings withWorldIds(boolean send) {
        return new XaeroMapSettings(send, claims, shownTo, chunkCoveragePercent, refreshSeconds,
                chunksPerRefresh, ownColour, sharedColour);
    }

    public XaeroMapSettings withClaims(boolean draw) {
        return new XaeroMapSettings(worldIds, draw, shownTo, chunkCoveragePercent, refreshSeconds,
                chunksPerRefresh, ownColour, sharedColour);
    }

    public XaeroMapSettings withShownTo(MapAudience audience) {
        return new XaeroMapSettings(worldIds, claims, audience, chunkCoveragePercent, refreshSeconds,
                chunksPerRefresh, ownColour, sharedColour);
    }

    public XaeroMapSettings withChunkCoveragePercent(int percent) {
        return new XaeroMapSettings(worldIds, claims, shownTo, percent, refreshSeconds,
                chunksPerRefresh, ownColour, sharedColour);
    }

    public XaeroMapSettings withRefreshSeconds(int seconds) {
        return new XaeroMapSettings(worldIds, claims, shownTo, chunkCoveragePercent, seconds,
                chunksPerRefresh, ownColour, sharedColour);
    }

    public XaeroMapSettings withChunksPerRefresh(int chunks) {
        return new XaeroMapSettings(worldIds, claims, shownTo, chunkCoveragePercent, refreshSeconds,
                chunks, ownColour, sharedColour);
    }

    public XaeroMapSettings withOwnColour(NamedTextColor colour) {
        return new XaeroMapSettings(worldIds, claims, shownTo, chunkCoveragePercent, refreshSeconds,
                chunksPerRefresh, colour, sharedColour);
    }

    public XaeroMapSettings withSharedColour(NamedTextColor colour) {
        return new XaeroMapSettings(worldIds, claims, shownTo, chunkCoveragePercent, refreshSeconds,
                chunksPerRefresh, ownColour, colour);
    }
}
