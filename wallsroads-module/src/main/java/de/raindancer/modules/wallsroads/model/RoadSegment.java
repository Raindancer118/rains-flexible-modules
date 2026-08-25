package de.raindancer.modules.wallsroads.model;

import de.raindancer.core.world.geometry.ColumnPolygon.Column;
import de.raindancer.modules.wallsroads.service.TerrainReader;

/**
 * One column of a road, once it has been decided what the road has to do there.
 *
 * <p>The output of profiling and the input to building — deliberately a value with no behaviour, so
 * the decision (hard, worth testing) and the block placement (mechanical) never grow into each
 * other.
 */
public record RoadSegment(Column column, SegmentKind kind, int surfaceY, TerrainReader.Reading reading) {

    /** How far the road runs above the ground here — negative when it runs below it. */
    public int heightAboveGround() {
        return surfaceY - reading.groundY();
    }

    public boolean isUnderWater() {
        return reading.isUnderWater() && surfaceY <= reading.waterSurfaceY();
    }
}
