package de.raindancer.modules.xaeromap.model;

import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Objects;

/**
 * One place, reduced to what a waypoint on somebody's map needs.
 *
 * <p>Whole blocks rather than the exact double a place is stored at: a waypoint is a marker on a map,
 * the client's own field is an int, and half a block of precision is not something anybody navigates by.
 *
 * @param kind what sort of place this came from — {@code "home"}, {@code "warp"} — which is what
 *             decides its colour and which of the {@code /xaeromap} lists it appears in
 */
public record Waypoint(String id, String name, String kind, String dimensionKey,
                       int x, int y, int z, NamedTextColor colour) {

    public Waypoint {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        kind = kind == null || kind.isBlank() ? "place" : kind;
        dimensionKey = dimensionKey == null ? "" : dimensionKey;
    }

    /** The chat line a client turns into a button. */
    public String shareLine() {
        return XaeroShare.line(this);
    }

    /** The same place in another colour — what a per-kind colour setting does to it. */
    public Waypoint inColour(NamedTextColor newColour) {
        return new Waypoint(id, name, kind, dimensionKey, x, y, z, newColour);
    }
}
