package de.raindancer.modules.wallsroads.service;

import de.raindancer.core.world.build.BatchBuilder;
import de.raindancer.core.world.build.Ground;
import de.raindancer.core.world.geometry.ColumnPolygon;
import de.raindancer.core.world.safety.Spot;
import de.raindancer.modules.wallsroads.model.ElevationMode;
import de.raindancer.modules.wallsroads.model.RoadPath;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a {@link RoadPath} into blocks, and back. The footprint itself is Core's
 * ({@code Polyline#footprint}); the paving policy — one flat Y, or following the ground — is this
 * module's own, since nothing in Core does terrain-following yet.
 */
public final class RoadBuildService {

    /** How wide a window {@link #smoothedHeights} averages over, either side of a column. */
    private static final int SMOOTHING_RADIUS = 2;

    public Set<ColumnPolygon.Column> footprint(RoadPath road) {
        return road.path().footprint(road.width());
    }

    /**
     * One surface Y per footprint column: the road's fixed Y for {@link ElevationMode#FIXED_Y}, or a
     * moving-average of the ground height under the centreline for {@link ElevationMode#FOLLOW_TERRAIN}.
     */
    public Map<ColumnPolygon.Column, Integer> surfaceHeights(RoadPath road, Ground ground) {
        Set<ColumnPolygon.Column> footprint = footprint(road);
        Map<ColumnPolygon.Column, Integer> heights = new LinkedHashMap<>();
        if (road.elevationMode() == ElevationMode.FIXED_Y) {
            for (ColumnPolygon.Column column : footprint) {
                heights.put(column, road.fixedY());
            }
            return heights;
        }

        List<ColumnPolygon.Column> centreline = road.path().orderedColumns();
        List<Integer> rawGroundY = new ArrayList<>();
        for (ColumnPolygon.Column column : centreline) {
            rawGroundY.add(topSolidY(ground, road.world(), column));
        }
        List<Integer> smoothed = smoothedHeights(rawGroundY);

        // Assign every footprint column the smoothed height of its nearest centreline point.
        for (ColumnPolygon.Column column : footprint) {
            int nearestIndex = 0;
            long best = Long.MAX_VALUE;
            for (int i = 0; i < centreline.size(); i++) {
                ColumnPolygon.Column point = centreline.get(i);
                long dx = point.x() - column.x();
                long dz = point.z() - column.z();
                long distance = dx * dx + dz * dz;
                if (distance < best) {
                    best = distance;
                    nearestIndex = i;
                }
            }
            heights.put(column, smoothed.get(nearestIndex));
        }
        return heights;
    }

    /** Scans down from a reasonable height until it finds solid ground — bounded, so it terminates. */
    private int topSolidY(Ground ground, String world, ColumnPolygon.Column column) {
        int scanStart = 320;
        int scanFloor = -64;
        for (int y = scanStart; y >= scanFloor; y--) {
            String material = ground.materialAt(new Spot(world, column.x(), y, column.z()));
            if (material != null && !material.equals("AIR") && !material.equals("CAVE_AIR")
                    && !material.equals("VOID_AIR")) {
                return y + 1;
            }
        }
        return scanFloor;
    }

    private List<Integer> smoothedHeights(List<Integer> raw) {
        List<Integer> smoothed = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            int from = Math.max(0, i - SMOOTHING_RADIUS);
            int to = Math.min(raw.size() - 1, i + SMOOTHING_RADIUS);
            long sum = 0;
            for (int j = from; j <= to; j++) {
                sum += raw.get(j);
            }
            smoothed.add((int) Math.round(sum / (double) (to - from + 1)));
        }
        return smoothed;
    }

    public List<BatchBuilder.Placement> buildPlacements(RoadPath road, Map<ColumnPolygon.Column, Integer> heights) {
        List<BatchBuilder.Placement> placements = new ArrayList<>();
        String materialName = road.material().name();
        for (Map.Entry<ColumnPolygon.Column, Integer> entry : heights.entrySet()) {
            ColumnPolygon.Column column = entry.getKey();
            placements.add(new BatchBuilder.Placement(
                    new Spot(road.world(), column.x(), entry.getValue(), column.z()), materialName));
        }
        return placements;
    }

    public BatchBuilder newBuild(Ground ground, RoadPath road) {
        return new BatchBuilder(ground, buildPlacements(road, surfaceHeights(road, ground)));
    }

    /** The exact inverse: puts back whatever {@link RoadPath#snapshot()} recorded before this was built. */
    public BatchBuilder newTeardown(Ground ground, RoadPath road) {
        List<BatchBuilder.Placement> restore = road.snapshot().asRestorePlacements().stream()
                .map(p -> new BatchBuilder.Placement(p.spot(), p.material()))
                .toList();
        return new BatchBuilder(ground, restore);
    }
}
