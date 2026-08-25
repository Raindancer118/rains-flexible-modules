package de.raindancer.modules.wallsroads.model;

import de.raindancer.core.world.geometry.ColumnPolygon;
import org.bukkit.Material;

import java.util.List;

/**
 * An opening cut through a {@link Wall} where a {@link RoadPath} crosses it — found by geometric
 * intersection between the road's footprint and the wall's outline, never marked by hand.
 *
 * <h2>Shut and sealed are different things</h2>
 * <b>Shut</b> is a gate doing its job: the doors are closed, they can be opened again by anybody the
 * wall's owner allows, and they close themselves at nightfall if the wall is set to. <b>Sealed</b> is
 * the opening being bricked up in the wall's own material — a decision about the wall, not about the
 * gate, and it is what a road being torn up leaves behind.
 *
 * <p>One flag each, rather than one three-state field, because they are undone by different people
 * for different reasons: a guard opens a shut gate, an owner unseals a sealed one.
 */
public record Gate(String id, String wallId, String roadId, List<ColumnPolygon.Column> openingColumns,
                   int height, boolean sealed, boolean shut, Material doorMaterial) {

    public Gate {
        openingColumns = List.copyOf(openingColumns);
        doorMaterial = doorMaterial == null ? Material.OAK_FENCE : doorMaterial;
    }

    /** A freshly cut opening: open, unsealed, with a plain gate in it. */
    public Gate(String id, String wallId, String roadId, List<ColumnPolygon.Column> openingColumns,
                int height, boolean sealed) {
        this(id, wallId, roadId, openingColumns, height, sealed, false, Material.OAK_FENCE);
    }

    public Gate asSealed(boolean value) {
        return new Gate(id, wallId, roadId, openingColumns, height, value, shut, doorMaterial);
    }

    public Gate asShut(boolean value) {
        return new Gate(id, wallId, roadId, openingColumns, height, sealed, value, doorMaterial);
    }

    public Gate withDoor(Material material) {
        return new Gate(id, wallId, roadId, openingColumns, height, sealed, shut, material);
    }

    /** Whether anything can pass through right now. */
    public boolean isPassable() {
        return !sealed && !shut;
    }

    /** How wide the opening is, in blocks. */
    public int width() {
        return openingColumns.size();
    }
}
