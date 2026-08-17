package de.raindancer.modules.wallsroads.service;

import de.raindancer.core.world.geometry.ColumnPolygon;
import de.raindancer.modules.wallsroads.model.Gate;
import de.raindancer.modules.wallsroads.model.RoadPath;
import de.raindancer.modules.wallsroads.model.Wall;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Where a road cuts a wall open — decided by plain geometric intersection between the two shapes
 * Core already computes: a wall's outline columns and a road's buffered footprint. The geometry is
 * Core's ({@link ColumnPolygon#outlineColumns()}, {@code Polyline#footprint}); grouping the
 * intersection into openings and deciding how tall each one is is this module's own logic.
 */
public final class GateService {

    private static final int[] NEIGHBOUR_DX = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] NEIGHBOUR_DZ = {0, 0, 1, -1, 1, -1, 1, -1};

    /**
     * Every opening this road cuts through this wall, right now — one {@link Gate} per contiguous
     * run of outline columns the road's footprint touches, so a road that clips a corner and crosses
     * two separate faces of the wall gets two gates rather than one opening jumping across the gap
     * between them.
     */
    public List<Gate> detect(Wall wall, RoadPath road, int gateHeight) {
        Set<ColumnPolygon.Column> outline = new LinkedHashSet<>(wall.effectiveOutline().outlineColumns());
        Set<ColumnPolygon.Column> roadFootprint = road.path().footprint(road.width());

        Set<ColumnPolygon.Column> intersecting = new LinkedHashSet<>();
        for (ColumnPolygon.Column column : outline) {
            if (roadFootprint.contains(column)) {
                intersecting.add(column);
            }
        }
        if (intersecting.isEmpty()) {
            return List.of();
        }

        List<Gate> gates = new ArrayList<>();
        for (List<ColumnPolygon.Column> run : contiguousRuns(intersecting)) {
            gates.add(new Gate(UUID.randomUUID().toString(), wall.id(), road.id(), run, gateHeight, false));
        }
        return gates;
    }

    /** Flood-fills the intersection set into its connected pieces — an eight-neighbour walk. */
    private List<List<ColumnPolygon.Column>> contiguousRuns(Set<ColumnPolygon.Column> columns) {
        Set<ColumnPolygon.Column> remaining = new LinkedHashSet<>(columns);
        List<List<ColumnPolygon.Column>> runs = new ArrayList<>();
        while (!remaining.isEmpty()) {
            ColumnPolygon.Column start = remaining.iterator().next();
            List<ColumnPolygon.Column> run = new ArrayList<>();
            Deque<ColumnPolygon.Column> queue = new ArrayDeque<>();
            queue.add(start);
            remaining.remove(start);
            while (!queue.isEmpty()) {
                ColumnPolygon.Column at = queue.poll();
                run.add(at);
                for (int i = 0; i < NEIGHBOUR_DX.length; i++) {
                    ColumnPolygon.Column neighbour =
                            new ColumnPolygon.Column(at.x() + NEIGHBOUR_DX[i], at.z() + NEIGHBOUR_DZ[i]);
                    if (remaining.remove(neighbour)) {
                        queue.add(neighbour);
                    }
                }
            }
            runs.add(run);
        }
        return runs;
    }
}
