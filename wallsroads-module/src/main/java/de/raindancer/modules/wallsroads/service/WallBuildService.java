package de.raindancer.modules.wallsroads.service;

import de.raindancer.core.world.build.BatchBuilder;
import de.raindancer.core.world.build.Ground;
import de.raindancer.core.world.geometry.ColumnPolygon;
import de.raindancer.core.world.geometry.ColumnPolygon.Column;
import de.raindancer.core.world.safety.Spot;
import de.raindancer.modules.wallsroads.model.Gate;
import de.raindancer.modules.wallsroads.model.Wall;
import de.raindancer.modules.wallsroads.model.WallProfile;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns a {@link Wall} into blocks, and back.
 *
 * <h2>What was wrong with the wall this replaces</h2>
 * It was built at one height — the height of whoever marked it — for its whole length. On flat ground
 * that is a wall; on anything else it is a ribbon of stone that slices through every hill it meets and
 * floats over every dip, which is exactly what the first one built on the test server did. It also had
 * nothing on top: the "walkway" was a slab ledge one course below the parapet, which is a shelf, not a
 * wall-walk, and there was no way onto it.
 *
 * <p>So a wall is now built the way a road is: read the ground under every step of it, smooth it,
 * cap how fast it may climb, and carry its footings down to whatever is beneath. What sits on top is
 * a real cross-section — floor, parapet outside, parapet inside — with buttresses down the outer face
 * and a ladder up the inner one.
 */
public final class WallBuildService {

    /** How far a wall may step up or down per block along it. Higher reads as a staircase. */
    public static final int MAX_STEP = 1;

    /** How many columns either side the ground under the wall is averaged over. */
    private static final int SMOOTHING = 2;

    /** How far below its base a footing will chase the ground before it is left as a stub. */
    private static final int MAX_FOUNDATION_DEPTH = 24;

    /** Clear air kept above the wall-walk, so it can actually be walked. */
    private static final int WALK_HEADROOM = 3;

    private final TerrainReader terrain = new TerrainReader();
    private final GateService gates = new GateService();

    // ------------------------------------------------------------------------------- the plan

    /** One step along the wall: where it sits, and which columns it occupies across its thickness. */
    public record Course(Column centre, int baseY, List<Column> across) {

        /** The outer face — the side away from what the wall encloses. */
        public Column outer() {
            return across.get(0);
        }

        /** And the inner one. */
        public Column inner() {
            return across.get(across.size() - 1);
        }
    }

    /**
     * The whole wall, step by step, before a single block is decided.
     *
     * <p>Pure: given a {@link Ground} it reads heights, and given {@code null} it uses the marked base
     * throughout — which is what an estimate wants, and what the wall used to do everywhere.
     */
    public List<Course> plan(Wall wall, Ground ground) {
        List<Column> ring = wall.effectiveOutline().outlineColumns();
        if (ring.size() < 3) {
            return List.of();
        }
        int[] base = baseHeights(wall, ring, ground);

        List<Course> courses = new ArrayList<>(ring.size());
        for (int i = 0; i < ring.size(); i++) {
            courses.add(new Course(ring.get(i), base[i],
                    crossSection(wall, ring, i, wall.thickness())));
        }
        return courses;
    }

    /**
     * What height the wall sits at along its length: the ground under it, smoothed so a boulder does
     * not put a notch in it, then capped so it steps rather than jumps.
     *
     * <p>Capped from both ends and around the ring, because a wall is closed: capping in one pass
     * leaves the seam where the last step meets the first as a cliff.
     */
    private int[] baseHeights(Wall wall, List<Column> ring, Ground ground) {
        int[] wanted = new int[ring.size()];
        if (ground == null) {
            java.util.Arrays.fill(wanted, wall.minY());
            return wanted;
        }
        for (int i = 0; i < ring.size(); i++) {
            wanted[i] = terrain.read(ground, wall.world(), ring.get(i)).groundY();
        }
        int[] smoothed = smoothAround(wanted, SMOOTHING);
        return capAround(smoothed, MAX_STEP);
    }

    /** A moving average that wraps, because the ring has no first or last step. */
    private static int[] smoothAround(int[] values, int radius) {
        int[] smoothed = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            long sum = 0;
            for (int offset = -radius; offset <= radius; offset++) {
                sum += values[Math.floorMod(i + offset, values.length)];
            }
            smoothed[i] = (int) Math.round(sum / (double) (radius * 2 + 1));
        }
        return smoothed;
    }

    /**
     * Caps the step between neighbours, all the way round and repeatedly.
     *
     * <p>Repeatedly because one pass around a ring can only carry a correction as far as it has left
     * to go: a spike met on the last step needs another lap to be smoothed into the first. It settles
     * quickly and is bounded by the number of steps, so it cannot run away.
     */
    private static int[] capAround(int[] values, int maxStep) {
        int[] capped = values.clone();
        for (int pass = 0; pass < capped.length; pass++) {
            boolean changed = false;
            for (int i = 0; i < capped.length; i++) {
                int next = (i + 1) % capped.length;
                int difference = capped[next] - capped[i];
                if (Math.abs(difference) <= maxStep) {
                    continue;
                }
                // Always lower the higher of the two: raising one would lift the wall off the ground
                // it is supposed to be standing on.
                if (difference > 0) {
                    capped[next] = capped[i] + maxStep;
                } else {
                    capped[i] = capped[next] + maxStep;
                }
                changed = true;
            }
            if (!changed) {
                return capped;
            }
        }
        return capped;
    }

    /**
     * The columns across the wall at one step, <strong>outer face first</strong>.
     *
     * <p>Perpendicular to where the wall is actually going, taken from the step before and after —
     * measured from one step only, every corner would have its cross-section fanned out on the outside
     * and pinched on the inside.
     *
     * <p>Which end is "outer" is decided by asking the shape: the side that is not inside it.
     */
    public List<Column> crossSection(Wall wall, List<Column> ring, int index, int thickness) {
        Column here = ring.get(index);
        Column previous = ring.get(Math.floorMod(index - 1, ring.size()));
        Column next = ring.get((index + 1) % ring.size());

        double dx = next.x() - previous.x();
        double dz = next.z() - previous.z();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length == 0) {
            dx = 1;
            dz = 0;
            length = 1;
        }
        double perpX = -dz / length;
        double perpZ = dx / length;

        // Which way is out? One step along the normal, and ask the polygon.
        Column probe = new Column((int) Math.round(here.x() + perpX * 2),
                (int) Math.round(here.z() + perpZ * 2));
        int outward = wall.effectiveOutline().contains(probe) ? -1 : 1;

        Set<Column> ordered = new LinkedHashSet<>();
        for (double step = 0; step < thickness; step += 0.5) {
            double offset = (thickness - 1) / 2.0 - step;
            ordered.add(new Column(
                    (int) Math.round(here.x() + perpX * offset * outward),
                    (int) Math.round(here.z() + perpZ * offset * outward)));
        }
        return new ArrayList<>(ordered);
    }

    /** Where the wall-walk's floor sits above a given column — what a test and a screen both ask. */
    public int walkwayHeightAt(Wall wall, Ground ground, Column column) {
        List<Course> courses = plan(wall, ground);
        Course nearest = null;
        long closest = Long.MAX_VALUE;
        for (Course course : courses) {
            long dx = course.centre().x() - column.x();
            long dz = course.centre().z() - column.z();
            long distance = dx * dx + dz * dz;
            if (distance < closest) {
                closest = distance;
                nearest = course;
            }
        }
        return nearest == null ? wall.minY() + wall.height() : nearest.baseY() + wall.height();
    }

    // -------------------------------------------------------------------------------- building

    /** What a wall costs to build, without a world to build it in. */
    public List<BatchBuilder.Placement> buildPlacements(Wall wall, Set<Column> gateOpenings) {
        return buildPlacements(wall, gateOpenings, null);
    }

    public List<BatchBuilder.Placement> buildPlacements(Wall wall, Set<Column> gateOpenings, Ground ground) {
        WallProfile profile = wall.profile();
        List<Course> courses = plan(wall, ground);
        List<BatchBuilder.Placement> placements = new ArrayList<>();
        String material = wall.material().name();

        for (int index = 0; index < courses.size(); index++) {
            Course course = courses.get(index);
            int baseY = course.baseY();
            int topY = baseY + wall.height() - 1;

            for (Column column : course.across()) {
                if (profile.foundation() && ground != null) {
                    for (int y = baseY - 1; y >= footingFloor(ground, wall.world(), column, baseY); y--) {
                        placements.add(place(wall.world(), column, y, material));
                    }
                }
                for (int y = baseY; y <= topY; y++) {
                    placements.add(place(wall.world(), column, y, material));
                }
            }

            if (profile.walkway()) {
                walk(placements, wall, course, Set.of(), topY, profile);
            } else if (profile.battlements()) {
                // No walk to stand on, so the crenellation is the wall's own top course.
                for (Column column : course.across()) {
                    if (!isMerlonGap(column, profile.merlonPeriod())) {
                        continue;
                    }
                    placements.add(place(wall.world(), column, topY, "AIR"));
                }
            }

            boolean atAButtress = profile.hasButtresses() && index % profile.buttressSpacing() == 0;
            if (atAButtress) {
                buttress(placements, wall, course, ground, profile);
            }
            if (profile.plinth()) {
                plinth(placements, wall, course, ground, material);
            }
            if (profile.cornice()) {
                cornice(placements, wall, course, index, topY, profile, material);
            }
            if (profile.arches() && !atAButtress && wall.thickness() >= 2) {
                recess(placements, wall, course, index, topY, profile, gateOpenings);
            }
            if (profile.hasLadders() && index % profile.ladderSpacing() == 0) {
                ladder(placements, wall, course, topY, profile);
            }
        }

        if (profile.hasTowers()) {
            towers(placements, wall, courses, gateOpenings, profile);
        }

        // Any column asked for directly and not part of a gate: cleared outright, which is what a
        // caller handing over a bare set of columns means by one.
        Set<Column> archColumns = new LinkedHashSet<>();
        for (Gate gate : wall.gates()) {
            archColumns.addAll(gate.openingColumns());
        }
        for (Column column : gateOpenings) {
            if (archColumns.contains(column)) {
                continue;
            }
            for (int y = wall.minY(); y <= wall.minY() + wall.height() + WALK_HEADROOM; y++) {
                placements.add(place(wall.world(), column, y, "AIR"));
            }
        }

        // Last, and after everything: the wall is built straight through where its gates are, and the
        // arch is then cut out of what was built.
        //
        // The other way round — leaving the gate's columns out of the build — is what this used to do,
        // and it is why a gateway came out as a ragged notch with the courses above it simply missing.
        // A gate is a hole in a wall, so there has to be a wall for it to be a hole in.
        for (Gate gate : wall.gates()) {
            if (gate.sealed()) {
                // Bricked up: it is wall now, and the wall built above already covers it.
                continue;
            }
            String fill = gate.shut() ? gate.doorMaterial().name() : "AIR";
            for (Spot spot : gates.archSpots(wall, gate, wall.thickness() / 2)) {
                placements.add(new BatchBuilder.Placement(spot, fill));
            }
        }
        return placements;
    }

    /**
     * The wall-walk: a floor across the full thickness, a crenellated parapet on the outer edge, a low
     * one on the inner, and clear air between them.
     *
     * <p>The parapet is on the edges and the floor is everything, rather than the other way round: a
     * walk you cannot stand on the edge of is a corridor, and one with no inner rail is a fall onto
     * your own town.
     */
    private void walk(List<BatchBuilder.Placement> placements, Wall wall, Course course,
                      Set<Column> gateOpenings, int topY, WallProfile profile) {
        int floorY = topY + 1;
        String floor = profile.walkwayMaterial().name();
        String material = wall.material().name();

        for (Column column : course.across()) {
            if (gateOpenings.contains(column)) {
                continue;
            }
            placements.add(place(wall.world(), column, floorY, floor));
            // Head height, so a walk under a hillside or a tree is still a walk.
            for (int y = floorY + 1; y <= floorY + WALK_HEADROOM; y++) {
                placements.add(place(wall.world(), column, y, "AIR"));
            }
        }

        Column outer = course.outer();
        Column inner = course.inner();
        if (!gateOpenings.contains(outer)) {
            placements.add(place(wall.world(), outer, floorY + 1, material));
            // The merlon: the tooth of the battlement, with the gaps left open between them.
            if (!profile.battlements() || !isMerlonGap(outer, profile.merlonPeriod())) {
                placements.add(place(wall.world(), outer, floorY + 2, material));
            }
        }
        if (!gateOpenings.contains(inner) && !inner.equals(outer)) {
            placements.add(place(wall.world(), inner, floorY + 1, material));
        }
    }

    /**
     * A buttress: a pier standing one block proud of the outer face, from the ground to the walk.
     *
     * <p>What makes a long wall read as a castle wall rather than as a fence — the eye needs something
     * to measure its length against, and a flat face gives it nothing.
     */
    private void buttress(List<BatchBuilder.Placement> placements, Wall wall, Course course,
                          Ground ground, WallProfile profile) {
        Column outer = course.outer();
        Column beyond = beyond(course);
        String material = profile.buttressMaterial().name();
        int top = course.baseY() + wall.height();
        int floor = ground == null ? course.baseY()
                : footingFloor(ground, wall.world(), beyond, course.baseY());

        for (int y = floor; y <= top; y++) {
            placements.add(place(wall.world(), beyond, y, material));
        }
        // One course of shoulder where it meets the wall, so it reads as built into it.
        placements.add(place(wall.world(), outer, top, material));
    }

    /**
     * The base course: one block proud of the outer face, along the whole wall.
     *
     * <p>Every reference wall has one, and it is why a castle wall meets the ground instead of being
     * stuck into it — the eye reads the widening as weight.
     */
    private void plinth(List<BatchBuilder.Placement> placements, Wall wall, Course course,
                        Ground ground, String material) {
        Column beyond = beyond(course);
        int from = ground == null ? course.baseY()
                : footingFloor(ground, wall.world(), beyond, course.baseY());
        for (int y = from; y <= course.baseY() + 1; y++) {
            placements.add(place(wall.world(), beyond, y, material));
        }
    }

    /**
     * The cornice: the course under the walk, corbelled one block out, with a light set into it now
     * and then.
     *
     * <p>This is the machicolation of every castle wall worth looking at — the walk is carried past
     * the face rather than stopping level with it, and the shadow that throws is most of what makes
     * the wall read as tall.
     */
    private void cornice(List<BatchBuilder.Placement> placements, Wall wall, Course course, int index,
                         int topY, WallProfile profile, String material) {
        Column beyond = beyond(course);
        boolean lit = profile.isLit() && index % profile.lanternSpacing() == 0;
        placements.add(place(wall.world(), beyond, topY,
                lit ? profile.lantern().name() : material));
    }

    /**
     * The recessed panel between two buttresses — the arch of the reference walls.
     *
     * <p>Only ever the outermost layer, and only on a wall at least two thick: a recess that reaches
     * the inner face is a hole, and a wall you can see daylight through is a fence. The top two
     * courses are left alone so the recess ends in an arch rather than running into the cornice.
     */
    private void recess(List<BatchBuilder.Placement> placements, Wall wall, Course course, int index,
                        int topY, WallProfile profile, Set<Column> gateOpenings) {
        Column outer = course.outer();
        if (gateOpenings.contains(outer)) {
            return;
        }
        int from = course.baseY() + 2;
        int to = topY - 2;
        if (to < from) {
            return;
        }
        // The shoulders of the arch: the two courses nearest a buttress stop short, so the panel
        // curves into it rather than ending in a square notch.
        int fromButtress = Math.min(index % profile.buttressSpacing(),
                profile.buttressSpacing() - index % profile.buttressSpacing());
        int shoulder = Math.max(0, 2 - fromButtress);
        for (int y = from; y <= to - shoulder; y++) {
            placements.add(place(wall.world(), outer, y, "AIR"));
        }
    }

    /** A ladder up the inner face, from the ground to the walk. */
    private void ladder(List<BatchBuilder.Placement> placements, Wall wall, Course course, int topY,
                        WallProfile profile) {
        Column inner = course.inner();
        int dx = Integer.signum(inner.x() - course.outer().x());
        int dz = Integer.signum(inner.z() - course.outer().z());
        if (dx == 0 && dz == 0) {
            return;
        }
        Column against = inner.offset(dx, dz);
        for (int y = course.baseY(); y <= topY + 1; y++) {
            placements.add(place(wall.world(), against, y, "LADDER"));
        }
    }

    /** The column one step further out than the outer face — where a buttress stands. */
    private static Column beyond(Course course) {
        Column outer = course.outer();
        Column inner = course.inner();
        int dx = Integer.signum(outer.x() - inner.x());
        int dz = Integer.signum(outer.z() - inner.z());
        return outer.offset(dx, dz);
    }

    /**
     * Towers, at the marked corners first and then spaced along the runs between them.
     *
     * <p>Corners first because that is what a tower is for: a corner is the one place a wall cannot be
     * defended along its own face.
     */
    private void towers(List<BatchBuilder.Placement> placements, Wall wall, List<Course> courses,
                        Set<Column> gateOpenings, WallProfile profile) {
        String material = profile.towerMaterial().name();
        String floor = profile.walkwayMaterial().name();
        int reach = profile.towerWidth();

        for (Course course : towerCourses(wall, courses, profile)) {
            int walkY = course.baseY() + wall.height();
            int roofY = walkY + profile.towerRise();

            for (int dx = -reach; dx <= reach; dx++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    Column column = course.centre().offset(dx, dz);
                    if (gateOpenings.contains(column)) {
                        continue;
                    }
                    boolean onTheRing = Math.abs(dx) == reach || Math.abs(dz) == reach;

                    if (onTheRing) {
                        // The shaft, up to the platform, and the parapet standing on top of it.
                        for (int y = course.baseY(); y <= roofY; y++) {
                            placements.add(place(wall.world(), column, y, material));
                        }
                        boolean merlonGap = profile.battlements()
                                && isMerlonGap(column, profile.merlonPeriod());
                        placements.add(place(wall.world(), column, roofY + 1,
                                merlonGap ? "AIR" : material));
                        continue;
                    }

                    // Inside. Hollow between the two floors — a solid tower is a pillar, and nobody
                    // can stand in a pillar — but a tower with no floor at the top is a chimney, which
                    // is what these were: walls around a hole, with nothing to stand on.
                    placements.add(place(wall.world(), column, walkY, floor));
                    placements.add(place(wall.world(), column, roofY, floor));
                    for (int y = walkY + 1; y < roofY; y++) {
                        placements.add(place(wall.world(), column, y, "AIR"));
                    }
                    for (int y = roofY + 1; y <= roofY + WALK_HEADROOM; y++) {
                        placements.add(place(wall.world(), column, y, "AIR"));
                    }
                }
            }

            // And a way from the room at walk level up onto the platform.
            Column ladderAt = course.centre().offset(reach - 1, 0);
            for (int y = walkY + 1; y <= roofY; y++) {
                placements.add(place(wall.world(), ladderAt, y, "LADDER"));
            }
        }
    }

    /** Which steps get a tower: every marked corner, and one every {@code towerSpacing} between. */
    public List<Course> towerCourses(Wall wall, List<Course> courses, WallProfile profile) {
        List<Course> chosen = new ArrayList<>();
        List<Column> marked = new ArrayList<>(wall.outline().vertices());

        // Either side of every gateway: a gate is the one place a wall is deliberately weak, and a
        // pair of towers flanking it is what every castle answers that with.
        for (Gate gate : wall.gates()) {
            List<Column> opening = gate.openingColumns();
            if (opening.size() < 2) {
                continue;
            }
            marked.add(opening.get(0));
            marked.add(opening.get(opening.size() - 1));
        }

        for (Course course : courses) {
            boolean atACorner = marked.stream().anyMatch(corner ->
                    Math.abs(corner.x() - course.centre().x()) <= 1
                            && Math.abs(corner.z() - course.centre().z()) <= 1);
            if (atACorner) {
                chosen.add(course);
            }
        }
        for (int i = 0; i < courses.size(); i += profile.towerSpacing()) {
            Course candidate = courses.get(i);
            boolean nearAnother = chosen.stream().anyMatch(other ->
                    Math.abs(other.centre().x() - candidate.centre().x())
                            + Math.abs(other.centre().z() - candidate.centre().z())
                            < profile.towerSpacing() / 2);
            if (!nearAnother) {
                chosen.add(candidate);
            }
        }
        return chosen;
    }

    /** How far down a footing goes: to the first solid ground under it, within reason. */
    private int footingFloor(Ground ground, String world, Column column, int baseY) {
        int floor = baseY - MAX_FOUNDATION_DEPTH;
        for (int y = baseY - 1; y >= floor; y--) {
            String material = ground.materialAt(new Spot(world, column.x(), y, column.z()));
            if (material != null && !terrain.isClearable(material)) {
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

    /** Every column the wall occupies, for anything that only needs the footprint. */
    public Set<Column> footprint(Wall wall) {
        Set<Column> columns = new LinkedHashSet<>();
        for (Course course : plan(wall, null)) {
            columns.addAll(course.across());
        }
        return columns;
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
