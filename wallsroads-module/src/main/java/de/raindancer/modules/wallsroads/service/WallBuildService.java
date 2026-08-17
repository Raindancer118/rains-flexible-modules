package de.raindancer.modules.wallsroads.service;

import de.raindancer.core.world.build.BatchBuilder;
import de.raindancer.core.world.build.Ground;
import de.raindancer.core.world.geometry.ColumnPolygon;
import de.raindancer.core.world.geometry.Polyline;
import de.raindancer.core.world.safety.Spot;
import de.raindancer.modules.wallsroads.model.Wall;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns a {@link Wall} into blocks, and back — the decision of thickness/height/corner-style is the
 * module's; the geometry ({@link ColumnPolygon}/{@link Polyline}) and the paced placement
 * ({@link BatchBuilder}) are Core's. See the plan: this is composition, not a second geometry engine.
 */
public final class WallBuildService {

    /**
     * The columns this wall occupies: its outline (rounded first, if configured), thickened outward
     * using {@link Polyline#footprint(double)} on the outline treated as a closed path — reusing the
     * exact same buffering math a road uses, rather than writing a separate polygon-offset routine.
     */
    public Set<ColumnPolygon.Column> footprint(Wall wall) {
        ColumnPolygon polygon = wall.effectiveOutline();
        List<ColumnPolygon.Column> ring = new ArrayList<>(polygon.outlineColumns());
        if (ring.size() < 2) {
            return Set.of();
        }
        if (!ring.get(0).equals(ring.get(ring.size() - 1))) {
            ring.add(ring.get(0));
        }
        return new Polyline(ring).footprint(wall.thickness());
    }

    /** The queue a fresh build needs, with any gate opening columns left out. */
    public List<BatchBuilder.Placement> buildPlacements(Wall wall, Set<ColumnPolygon.Column> gateOpenings) {
        Set<ColumnPolygon.Column> footprint = footprint(wall);
        List<BatchBuilder.Placement> placements = new ArrayList<>();
        String materialName = wall.material().name();
        for (ColumnPolygon.Column column : footprint) {
            if (gateOpenings.contains(column)) {
                continue;
            }
            for (int y = wall.minY(); y < wall.minY() + wall.height(); y++) {
                placements.add(new BatchBuilder.Placement(new Spot(wall.world(), column.x(), y, column.z()),
                        materialName));
            }
        }
        return placements;
    }

    public BatchBuilder newBuild(Ground ground, Wall wall, Set<ColumnPolygon.Column> gateOpenings) {
        return new BatchBuilder(ground, buildPlacements(wall, gateOpenings));
    }

    /** The exact inverse: puts back whatever {@link Wall#snapshot()} recorded before this was built. */
    public BatchBuilder newTeardown(Ground ground, Wall wall) {
        List<BatchBuilder.Placement> restore = wall.snapshot().asRestorePlacements().stream()
                .map(p -> new BatchBuilder.Placement(p.spot(), p.material()))
                .toList();
        return new BatchBuilder(ground, restore);
    }

    /** Rebuilds only the columns of one specific opening — a gate's "seal" action. */
    public BatchBuilder newSeal(Ground ground, Wall wall, Set<ColumnPolygon.Column> opening) {
        List<BatchBuilder.Placement> placements = new ArrayList<>();
        String materialName = wall.material().name();
        for (ColumnPolygon.Column column : opening) {
            for (int y = wall.minY(); y < wall.minY() + wall.height(); y++) {
                placements.add(new BatchBuilder.Placement(new Spot(wall.world(), column.x(), y, column.z()),
                        materialName));
            }
        }
        return new BatchBuilder(ground, placements);
    }

    /**
     * Cuts a fresh opening into a wall that is already standing — used when a road is built after
     * the wall it crosses already exists, so the wall's own footprint has to be carved into rather
     * than simply left out of a build that already happened.
     */
    public BatchBuilder newCut(Ground ground, Wall wall, Set<ColumnPolygon.Column> opening, int gateHeight) {
        List<BatchBuilder.Placement> placements = new ArrayList<>();
        int cutTo = wall.minY() + Math.min(gateHeight, wall.height());
        for (ColumnPolygon.Column column : opening) {
            for (int y = wall.minY(); y < cutTo; y++) {
                placements.add(new BatchBuilder.Placement(new Spot(wall.world(), column.x(), y, column.z()),
                        "AIR"));
            }
        }
        return new BatchBuilder(ground, placements);
    }

    /** Every column currently occupied by any open (unsealed) gate on this wall. */
    public Set<ColumnPolygon.Column> openGateColumns(Wall wall) {
        Set<ColumnPolygon.Column> columns = new LinkedHashSet<>();
        wall.gates().stream().filter(gate -> !gate.sealed())
                .forEach(gate -> columns.addAll(gate.openingColumns()));
        return columns;
    }
}
