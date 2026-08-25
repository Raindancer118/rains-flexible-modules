package de.raindancer.modules.wallsroads.model;

/** What one stretch of road has to be, given what it is crossing. */
public enum SegmentKind {

    /** Laid on the ground, which is where a road is unless something is in the way. */
    GROUND,

    /** Carried above the ground: a ravine, a river, a shallow crossing. Needs deck, railings, piers. */
    BRIDGE,

    /** Cut through ground that is above it: a hill, a cliff. Needs a bore, a lining and light. */
    TUNNEL,

    /** Under water: a long, deep crossing, walled in glass so the sea is the view rather than the end. */
    GLASS_TUNNEL;

    public boolean isEnclosed() {
        return this == TUNNEL || this == GLASS_TUNNEL;
    }

    public boolean isCarried() {
        return this == BRIDGE;
    }
}
