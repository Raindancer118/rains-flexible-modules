package de.raindancer.modules.wallsroads.model;

import de.raindancer.core.world.geometry.ColumnPolygon;

import java.util.List;

/**
 * An opening cut through a {@link Wall} where a {@link RoadPath} crosses it — found by geometric
 * intersection between the road's footprint and the wall's outline, never marked by hand.
 * Immutable; {@link #sealed()} is the one thing that changes, via {@link #asSealed(boolean)}.
 */
public record Gate(String id, String wallId, String roadId, List<ColumnPolygon.Column> openingColumns,
                   int height, boolean sealed) {

    public Gate {
        openingColumns = List.copyOf(openingColumns);
    }

    public Gate asSealed(boolean value) {
        return new Gate(id, wallId, roadId, openingColumns, height, value);
    }
}
