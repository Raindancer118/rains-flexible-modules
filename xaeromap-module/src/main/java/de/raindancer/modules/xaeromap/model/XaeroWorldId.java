package de.raindancer.modules.xaeromap.model;

import java.util.UUID;

/**
 * The one number that stops every world on this server sharing a single map.
 *
 * <p>Xaero's Minimap and World Map file their cached map data under an id the server tells them. Told
 * nothing, a client falls back on the server address — so on a Bukkit server every world is the same
 * "world" as far as the map is concerned: the nether's tunnels are drawn over the overworld's coastline,
 * a farm world overwrites the survival map, and walking through a portal shows a map of somewhere else.
 * There is nothing the player can do about it from their side.
 *
 * <p>The packet is a marker byte and an int, on {@code xaerominimap:main} and {@code xaeroworldmap:main}
 * — the two mods read the same one.
 *
 * <h2>Why the world's uuid and not its name</h2>
 * A world folder that is deleted and generated again — which is exactly what a farm world does — comes
 * back with a new uuid and therefore a fresh, empty map, which is the honest answer: the old map is of
 * terrain that no longer exists. Keyed on the name instead, the client would keep drawing the previous
 * world's coastlines over the new one until somebody walked every chunk again.
 */
public final class XaeroWorldId {

    /** Xaero's Minimap. */
    public static final String MINIMAP_CHANNEL = "xaerominimap:main";
    /** Xaero's World Map. Same payload, separate channel — a client may have either or both. */
    public static final String WORLDMAP_CHANNEL = "xaeroworldmap:main";

    /** The only packet either channel carries from us: "this is which world you are in". */
    private static final byte LEVEL_MAP_PROPERTIES = 0;

    private XaeroWorldId() {
    }

    /** The id for a world, from its uuid. */
    public static int of(UUID worldId) {
        return worldId == null ? 0 : worldId.hashCode();
    }

    /** The packet itself: the marker, then the id, big-endian as everything on the wire is. */
    public static byte[] packet(int id) {
        return new byte[] {
                LEVEL_MAP_PROPERTIES,
                (byte) (id >>> 24), (byte) (id >>> 16), (byte) (id >>> 8), (byte) id };
    }
}
