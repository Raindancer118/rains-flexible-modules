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
import de.raindancer.core.world.build.BatchBuilder;
import de.raindancer.core.world.safety.Spot;
import de.raindancer.modules.wallsroads.model.Wall;

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

    /**
     * Every block of the opening, as an arch.
     *
     * <h2>Why an arch and not a rectangle</h2>
     * A rectangular hole is what this used to cut, and it looked like exactly that: a gap punched
     * through a wall, with the courses above it left hanging over nothing. An arch is what carries a
     * wall over a gap in real building, and it is the one shape that makes an opening read as a gate
     * rather than as damage.
     *
     * <p>The shape is a semicircle sitting on straight jambs: a column {@code d} away from the middle
     * loses {@code radius - √(radius² - d²)} courses off the top, which is the circle. The passage
     * goes through the wall's whole thickness rather than only its face — the opening columns are the
     * road's own crossing, and the wall is as thick as it is.
     *
     * @param depth how far either side of the opening columns to reach, so the passage clears a wall
     *              thicker than the road that cut it
     */
    public List<Spot> archSpots(Wall wall, Gate gate, int depth) {
        List<ColumnPolygon.Column> columns = gate.openingColumns();
        if (columns.isEmpty()) {
            return List.of();
        }
        // The middle of the opening, and how far the furthest column is from it.
        double centreX = columns.stream().mapToInt(ColumnPolygon.Column::x).average().orElse(0);
        double centreZ = columns.stream().mapToInt(ColumnPolygon.Column::z).average().orElse(0);
        double radius = 0;
        for (ColumnPolygon.Column column : columns) {
            radius = Math.max(radius, Math.hypot(column.x() - centreX, column.z() - centreZ));
        }

        int wallTop = wall.minY() + wall.height();
        int crown = Math.min(wall.minY() + gate.height(), wallTop);

        List<Spot> spots = new ArrayList<>();
        for (ColumnPolygon.Column column : columns) {
            double distance = Math.hypot(column.x() - centreX, column.z() - centreZ);
            // How much this column's top is pulled down by the curve.
            int fall = radius <= 0 ? 0
                    : (int) Math.round(radius - Math.sqrt(Math.max(0, radius * radius - distance * distance)));
            int top = Math.max(wall.minY() + 1, crown - fall);

            for (int step = -depth; step <= depth; step++) {
                for (int y = wall.minY(); y < top; y++) {
                    spots.add(new Spot(wall.world(), column.x() + acrossX(columns) * step, y,
                            column.z() + acrossZ(columns) * step));
                }
            }
        }
        return spots;
    }

    /**
     * Which way the passage runs — across the line of the opening.
     *
     * <p>The opening's own columns lie along the wall, so the passage is at right angles to them.
     * A one-column gate has no line of its own, and is treated as running north-south, which is the
     * same guess the cross-section maths makes elsewhere.
     */
    private static int acrossX(List<ColumnPolygon.Column> columns) {
        if (columns.size() < 2) {
            return 0;
        }
        return columns.get(0).z() == columns.get(columns.size() - 1).z() ? 0 : 1;
    }

    private static int acrossZ(List<ColumnPolygon.Column> columns) {
        if (columns.size() < 2) {
            return 1;
        }
        return columns.get(0).z() == columns.get(columns.size() - 1).z() ? 1 : 0;
    }

    /** The blocks that close this gate: its doors, filling exactly the arch it cut. */
    public List<BatchBuilder.Placement> shutPlacements(Wall wall, Gate gate) {
        String material = gate.doorMaterial().name();
        List<BatchBuilder.Placement> placements = new ArrayList<>();
        for (Spot spot : archSpots(wall, gate, wall.thickness() / 2)) {
            placements.add(new BatchBuilder.Placement(spot, material));
        }
        return placements;
    }

    /** And the blocks that open it again: the same arch, cleared. */
    public List<BatchBuilder.Placement> openPlacements(Wall wall, Gate gate) {
        List<BatchBuilder.Placement> placements = new ArrayList<>();
        for (Spot spot : archSpots(wall, gate, wall.thickness() / 2)) {
            placements.add(new BatchBuilder.Placement(spot, "AIR"));
        }
        return placements;
    }

    /** Which gate on this wall, if any, has a block at this position. */
    public java.util.Optional<Gate> gateAt(Wall wall, Spot spot) {
        int top = wall.minY() + wall.height();
        if (spot.y() < wall.minY() || spot.y() >= top) {
            return java.util.Optional.empty();
        }
        ColumnPolygon.Column column = new ColumnPolygon.Column(spot.x(), spot.z());
        return wall.gates().stream().filter(gate -> gate.openingColumns().contains(column)).findFirst();
    }
}
