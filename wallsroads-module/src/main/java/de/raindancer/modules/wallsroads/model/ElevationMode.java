package de.raindancer.modules.wallsroads.model;

/** How a road's surface height is decided. */
public enum ElevationMode {
    /** Always at the same Y, the one recorded on the {@link RoadPath}. */
    FIXED_Y,
    /** Follows the ground, smoothed by a moving average along the centreline. */
    FOLLOW_TERRAIN
}
