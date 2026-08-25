package de.raindancer.modules.wallsroads.service;

import de.raindancer.core.world.build.BatchBuilder;
import de.raindancer.core.world.build.Ground;
import de.raindancer.core.world.geometry.ColumnPolygon;
import de.raindancer.core.world.geometry.ColumnPolygon.Column;
import de.raindancer.core.world.geometry.Polyline;
import de.raindancer.core.world.safety.Spot;
import de.raindancer.modules.wallsroads.model.Wall;
import de.raindancer.modules.wallsroads.model.WallProfile;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns a {@link Wall} into blocks, and back.
 *
 * <p>The geometry is Core's and the paced placement is Core's; what belongs here is what a wall
 * <em>is</em> — how thick, how tall, where its footings go, whether it is crenellated, and where its
 * towers stand.
 */
public final class WallBuildService {

    /** How far below the marked base a footing will chase the ground before giving up. */
    private static final int MAX_FOUNDATION_DEPTH = 24;

    /**
     * The columns this wall occupies: its outline (rounded first, if configured), thickened outward
     * using {@link Polyline#footprint(double)} on the outline treated as a closed path — reusing the
     * exact same buffering math a road uses, rather than writing a separate polygon-offset routine.
     */
    public Set<Column> footprint(Wall wall) {
        ColumnPolygon polygon = wall.effectiveOutline();
        List<Column> ring = new ArrayList<>(polygon.outlineColumns());
        if (ring.size() < 2) {
            return Set.of();
        }
        if (!ring.get(0).equals(ring.get(ring.size() - 1))) {
            ring.add(ring.get(0));
        }
        return new Polyline(ring).footprint(wall.thickness());
    }

    /** What a wall costs to build, without a world to build it in. */
    public List<BatchBuilder.Placement> buildPlacements(Wall wall, Set<Column> gateOpenings) {
        return buildPlacements(wall, gateOpenings, null);
    }

    /**
     * The queue a fresh build needs, with any gate opening columns left out.
     *
     * <p>{@code ground} may be {@code null} — an estimate does not need a world. Without one the
     * wall is built from its marked base upward and nothing is footed, which is exactly what the
     * wall looked like before profiles existed.
     */
    public List<BatchBuilder.Placement> buildPlacements(Wall wall, Set<Column> gateOpenings, Ground ground) {
        WallProfile profile = wall.profile();
        Set<Column> footprint = footprint(wall);
        List<BatchBuilder.Placement> placements = new ArrayList<>();
        String material = wall.material().name();
        int baseY = wall.minY();
        int topY = baseY + wall.height() - 1;

        for (Column column : footprint) {
            if (gateOpenings.contains(column)) {
                continue;
            }
            if (profile.foundation() && ground != null) {
                for (int y = baseY - 1; y >= footingFloor(ground, wall.world(), column, baseY); y--) {
                    placements.add(place(wall.world(), column, y, material));
                }
            }
            for (int y = baseY; y <= topY; y++) {
                boolean merlonGap = y == topY && profile.battlements()
                        && isMerlonGap(column, profile.merlonPeriod());
                // Cleared rather than skipped: the gap between two merlons is what a battlement is,
                // and a wall rebuilt where something already stands has to cut it out.
                placements.add(place(wall.world(), column, y, merlonGap ? "AIR" : material));
            }
        }

        if (profile.walkway()) {
            walkway(placements, wall, footprint, gateOpenings, topY, profile);
        }
        if (profile.hasTowers()) {
            towers(placements, wall, gateOpenings, topY, profile);
        }
        return placements;
    }

    /**
     * The ledge behind the parapet, one below the top and one block inside the wall.
     *
     * <p>Inside rather than outside: a walkway on the outer face is a step for whoever is besieging
     * you.
     */
    private void walkway(List<BatchBuilder.Placement> placements, Wall wall, Set<Column> footprint,
                         Set<Column> gateOpenings, int topY, WallProfile profile) {
        ColumnPolygon outline = wall.effectiveOutline();
        for (Column column : footprint) {
            for (Column inward : neighbours(column)) {
                if (footprint.contains(inward) || gateOpenings.contains(inward)
                        || !outline.contains(inward)) {
                    continue;
                }
                placements.add(place(wall.world(), inward, topY - 1, profile.walkwayMaterial().name()));
            }
        }
    }

    /**
     * Towers, at the marked corners first and then spaced along the runs between them.
     *
     * <p>Corners first because that is where a tower is for: a corner is the one place a wall cannot
     * be defended along its own face.
     */
    private void towers(List<BatchBuilder.Placement> placements, Wall wall, Set<Column> gateOpenings,
                        int topY, WallProfile profile) {
        String material = profile.towerMaterial().name();
        int height = topY + profile.towerRise();
        int reach = profile.towerWidth();

        for (Column centre : towerCentres(wall, profile)) {
            for (int dx = -reach; dx <= reach; dx++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    Column column = centre.offset(dx, dz);
                    if (gateOpenings.contains(column)) {
                        continue;
                    }
                    boolean edge = Math.abs(dx) == reach || Math.abs(dz) == reach;
                    if (!edge) {
                        // Hollow: a solid tower is a pillar, and nobody can stand in a pillar.
                        continue;
                    }
                    for (int y = wall.minY(); y <= height; y++) {
                        boolean merlonGap = y == height && profile.battlements()
                                && isMerlonGap(column, profile.merlonPeriod());
                        placements.add(place(wall.world(), column, y, merlonGap ? "AIR" : material));
                    }
                }
            }
        }
    }

    /** Where the towers go: every marked corner, plus a tower every {@code towerSpacing} between them. */
    public List<Column> towerCentres(Wall wall, WallProfile profile) {
        List<Column> vertices = wall.effectiveOutline().vertices();
        List<Column> centres = new ArrayList<>();
        List<Column> marked = wall.outline().vertices();
        centres.addAll(marked);

        List<Column> ring = wall.effectiveOutline().outlineColumns();
        for (int i = 0; i < ring.size(); i += profile.towerSpacing()) {
            Column candidate = ring.get(i);
            boolean nearAnother = centres.stream().anyMatch(other ->
                    Math.abs(other.x() - candidate.x()) + Math.abs(other.z() - candidate.z())
                            < profile.towerSpacing() / 2);
            if (!nearAnother) {
                centres.add(candidate);
            }
        }
        return vertices.isEmpty() ? List.of() : centres;
    }

    /** How far down a footing goes: to the first solid ground under the wall, within reason. */
    private int footingFloor(Ground ground, String world, Column column, int baseY) {
        int floor = baseY - MAX_FOUNDATION_DEPTH;
        for (int y = baseY - 1; y >= floor; y--) {
            String material = ground.materialAt(new Spot(world, column.x(), y, column.z()));
            if (material != null && !material.equals("AIR") && !material.equals("CAVE_AIR")
                    && !material.equals("VOID_AIR") && !material.equals("WATER")) {
                return y + 1;
            }
        }
        return floor;
    }

    /**
     * Whether this column is one of the gaps in a battlement.
     *
     * <p>Decided from the column's own coordinates rather than from its position along the wall, so
     * two runs meeting at a corner line up instead of restarting the pattern.
     */
    private static boolean isMerlonGap(Column column, int period) {
        return Math.floorMod(column.x() + column.z(), period * 2) < period;
    }

    private static Set<Column> neighbours(Column column) {
        return new LinkedHashSet<>(List.of(
                column.offset(1, 0), column.offset(-1, 0),
                column.offset(0, 1), column.offset(0, -1)));
    }

    public BatchBuilder newBuild(Ground ground, Wall wall, Set<Column> gateOpenings) {
        return new BatchBuilder(ground, buildPlacements(wall, gateOpenings, ground));
    }

    /** The exact inverse: puts back whatever {@link Wall#snapshot()} recorded before this was built. */
    public BatchBuilder newTeardown(Ground ground, Wall wall) {
        return new BatchBuilder(ground, wall.snapshot().asRestorePlacements());
    }

    /** Rebuilds only the columns of one specific opening — a gate's "seal" action. */
    public List<BatchBuilder.Placement> sealPlacements(Wall wall, Set<Column> opening) {
        List<BatchBuilder.Placement> placements = new ArrayList<>();
        String material = wall.material().name();
        for (Column column : opening) {
            for (int y = wall.minY(); y < wall.minY() + wall.height(); y++) {
                placements.add(place(wall.world(), column, y, material));
            }
        }
        return placements;
    }

    /** The blocks a fresh opening removes from a wall that is already standing. */
    public List<BatchBuilder.Placement> cutPlacements(Wall wall, Set<Column> opening, int gateHeight) {
        List<BatchBuilder.Placement> placements = new ArrayList<>();
        int cutTo = wall.minY() + Math.min(gateHeight, wall.height());
        for (Column column : opening) {
            for (int y = wall.minY(); y < cutTo; y++) {
                placements.add(place(wall.world(), column, y, "AIR"));
            }
        }
        return placements;
    }

    /** Every column currently occupied by any open (unsealed) gate on this wall. */
    public Set<Column> openGateColumns(Wall wall) {
        Set<Column> columns = new LinkedHashSet<>();
        wall.gates().stream().filter(gate -> !gate.sealed())
                .forEach(gate -> columns.addAll(gate.openingColumns()));
        return columns;
    }

    private static BatchBuilder.Placement place(String world, Column column, int y, String material) {
        return new BatchBuilder.Placement(new Spot(world, column.x(), y, column.z()), material);
    }
}
