package de.raindancer.modules.wallsroads.service;

import de.raindancer.core.world.build.BatchBuilder;
import de.raindancer.core.world.build.Ground;
import de.raindancer.core.world.geometry.ColumnPolygon.Column;
import de.raindancer.core.world.safety.Spot;
import de.raindancer.modules.wallsroads.model.RoadPath;
import de.raindancer.modules.wallsroads.model.PavingPalette;
import de.raindancer.modules.wallsroads.model.RoadProfile;
import de.raindancer.modules.wallsroads.model.RoadSegment;
import de.raindancer.modules.wallsroads.model.SegmentKind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A profiled route, turned into blocks.
 *
 * <h2>Order is the whole correctness argument</h2>
 * Everything is cleared before anything is placed. A tunnel whose lining goes up before its bore is
 * cleared is a lining with a hill still inside it; a sea tunnel whose glass goes up before the water
 * is cleared is a glass box full of water. So each segment emits its clearing first, then its
 * structure, and the queue is walked in order by {@link BatchBuilder}.
 *
 * <p>Nothing here decides <em>what</em> a stretch of road is — {@link RouteProfiler} did that. This
 * only knows how each kind is built.
 */
public final class RoadBuilder {

    private final TerrainReader terrain = new TerrainReader();

    /** Blocks between a bridge's piers. Closer looks like a wall, further looks unsupported. */
    private static final int PIER_SPACING = 8;

    /** How far down a pier will reach before it is left as a stub — a pier into the void is nothing. */
    private static final int MAX_PIER_DEPTH = 64;

    public List<BatchBuilder.Placement> placements(RoadPath road, List<RoadSegment> plan, Ground ground) {
        List<BatchBuilder.Placement> placements = new ArrayList<>();
        RoadProfile profile = road.profile();
        // A family of related blocks rather than one repeated forever: a road of a single material
        // reads as a texture stretched over the ground, which is what the first one looked like.
        PavingPalette palette = PavingPalette.forMaterial(road.material());

        // Worked out over the whole route before anything is placed, because a shell is only sound if
        // it is built against every face of the *finished* space. Segment by segment, the join between
        // two cross-sections on a bend is a face nobody lined — and one missing block under the sea is
        // a tunnel that floods on the next tick.
        //
        // Cleared, not enclosed: the road clears head height along its whole length, and the mouth of
        // a tunnel opens into the cleared space of the ordinary road beyond it. Sealing against the
        // enclosed part alone glazes that mouth shut — which is exactly what the first sea tunnel did,
        // a glass box you could see the road through and not walk onto it.
        Set<Spot> cleared = clearedOf(road, plan);
        Set<Spot> surface = surfaceOf(road, plan);

        // Paved and cleared over every column the road covers, in one pass, before anything is built
        // on top of it. Per-step cross-sections leave gaps on a diagonal, and a gap in the floor of a
        // sea tunnel is a hole the shell then has to fill — which is where the glass lattice came from.
        for (Map.Entry<Column, RoadSegment> entry : columnsAlong(road, plan).entrySet()) {
            Column column = entry.getKey();
            RoadSegment segment = entry.getValue();
            String floor = segment.kind().isEnclosed() && profile.tunnelFloor() != null
                    ? profile.tunnelFloor().name() : palette.at(column);
            placements.add(place(road.world(), column, segment.surfaceY(), floor));
            for (int y = segment.surfaceY() + 1; y <= segment.surfaceY() + profile.headroom(); y++) {
                placements.add(place(road.world(), column, y, "AIR"));
            }
        }

        for (int index = 0; index < plan.size(); index++) {
            RoadSegment segment = plan.get(index);
            List<Column> cross = crossSection(plan, index, road.width());
            if (cross.isEmpty()) {
                continue;
            }
            Column leftEdge = cross.get(0);
            Column rightEdge = cross.get(cross.size() - 1);
            int surfaceY = segment.surfaceY();
            int headroom = profile.headroom();


            switch (segment.kind()) {
                case BRIDGE -> {
                    edge(placements, road.world(), leftEdge, rightEdge, surfaceY + 1, profile.railing());
                    if (index % PIER_SPACING == 0) {
                        pier(placements, road, segment, profile, ground);
                    }
                }
                case TUNNEL, GLASS_TUNNEL -> {
                    // The shell is built in one pass below, over the whole cleared volume.
                }
                case GROUND -> {
                    if (profile.hasKerb()) {
                        // On the surface, not above it: a kerb is the edge of the road, and a course
                        // standing on top of it is a wall along a footpath.
                        placements.add(place(road.world(), leftEdge, surfaceY, palette.slab()));
                        placements.add(place(road.world(), rightEdge, surfaceY, palette.slab()));
                    }
                }
                default -> {
                }
            }

            lamps(placements, road, segment, index, leftEdge, rightEdge, surfaceY, profile, ground);
        }

        shell(placements, road, plan, cleared, surface, ground, profile);
        return placements;
    }

    /**
     * The shell around every enclosed stretch, in one pass over the whole cleared volume.
     *
     * <p>One pass, and over the same set the clearing used, because those are the two halves of one
     * statement: a shell is sound exactly when every face of the cleared space is either more cleared
     * space, road surface, or something solid. Built per step instead — which is how it was — the
     * shell only ever looks at that step's own cross-section, and every column the footprint covers
     * beyond it is a face nobody checked. Under the sea each of those is a hole.
     */
    private void shell(List<BatchBuilder.Placement> placements, RoadPath road, List<RoadSegment> plan,
                       Set<Spot> cleared, Set<Spot> surface, Ground ground, RoadProfile profile) {
        Map<Column, RoadSegment> byColumn = columnsAlong(road, plan);
        int headroom = profile.headroom();

        for (Map.Entry<Column, RoadSegment> entry : byColumn.entrySet()) {
            RoadSegment segment = entry.getValue();
            if (!segment.kind().isEnclosed()) {
                continue;
            }
            boolean glazed = segment.kind() == SegmentKind.GLASS_TUNNEL;
            String material = glazed ? profile.glass().name() : profile.tunnelLining().name();
            Column column = entry.getKey();

            for (int y = segment.surfaceY() + 1; y <= segment.surfaceY() + headroom; y++) {
                Spot inside = new Spot(road.world(), column.x(), y, column.z());
                for (Spot face : faces(inside)) {
                    if (cleared.contains(face) || surface.contains(face)) {
                        continue;
                    }
                    // A bored tunnel is lined all round — that is what makes it a tunnel rather than
                    // a hole in a hill. A glass one is sealed only where something has to be held
                    // back, so the sea bed stays the sea bed rather than being walled off behind
                    // panes nobody asked for.
                    if (glazed && ground != null && !needsSealing(ground, face)) {
                        continue;
                    }
                    placements.add(new BatchBuilder.Placement(face, material));
                }
            }
        }
    }

    /**
     * The columns across the road at one step, left edge first.
     *
     * <p>Perpendicular to where the road is actually going, taken from the step before and after —
     * measured from one step only, every bend would have its cross-section fanned out along the
     * outside of the curve and pinched on the inside.
     */
    public List<Column> crossSection(List<RoadSegment> plan, int index, double width) {
        Column here = plan.get(index).column();
        Column previous = plan.get(Math.max(0, index - 1)).column();
        Column next = plan.get(Math.min(plan.size() - 1, index + 1)).column();

        double dx = next.x() - previous.x();
        double dz = next.z() - previous.z();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length == 0) {
            dx = 1;
            dz = 0;
            length = 1;
        }
        // Perpendicular, unit length: the road's own left-and-right.
        double perpX = -dz / length;
        double perpZ = dx / length;

        double radius = Math.max(0, (width - 1) / 2.0);
        Set<Column> ordered = new LinkedHashSet<>();
        // Half-block steps so a diagonal cross-section has no gaps in it.
        for (double offset = -radius; offset <= radius + 1e-9; offset += 0.5) {
            ordered.add(new Column((int) Math.round(here.x() + perpX * offset),
                    (int) Math.round(here.z() + perpZ * offset)));
        }
        return new ArrayList<>(ordered);
    }

    /**
     * Air above the road.
     *
     * <p>On the ground this is what makes a road through a slope walkable rather than a stripe of
     * gravel with a hillside standing on it. In a tunnel it <em>is</em> the tunnel, and under water
     * it is what makes the tube dry. One block wider than the road inside a tunnel, so the lining
     * has somewhere to sit that is not the road itself.
     */
    private void clear(List<BatchBuilder.Placement> placements, String world, List<Column> cross,
                       int surfaceY, int headroom, SegmentKind kind) {
        for (Column column : cross) {
            for (int y = surfaceY + 1; y <= surfaceY + headroom; y++) {
                placements.add(place(world, column, y, "AIR"));
            }
        }
    }

    /**
     * Every space an enclosed stretch of this route hollows out.
     *
     * <p>Public because it is the thing worth asserting about: a shell is sound exactly when every
     * face of this set is either road that carries on or a block that was placed.
     */
    public Set<Spot> interiorOf(RoadPath road, List<RoadSegment> plan) {
        return clearedAlong(road, plan, true);
    }

    /**
     * Every space the road clears above itself, enclosed or not.
     *
     * <p>This is what a shell is built <em>against</em>: a tunnel's mouth opens into the ordinary road
     * beyond it, and that road's own head height is not something to glaze over.
     */
    public Set<Spot> clearedOf(RoadPath road, List<RoadSegment> plan) {
        return clearedAlong(road, plan, false);
    }

    private Set<Spot> clearedAlong(RoadPath road, List<RoadSegment> plan, boolean enclosedOnly) {
        Set<Spot> spaces = new LinkedHashSet<>();
        int headroom = road.profile().headroom();
        for (Map.Entry<Column, RoadSegment> entry : columnsAlong(road, plan).entrySet()) {
            RoadSegment segment = entry.getValue();
            if (enclosedOnly && !segment.kind().isEnclosed()) {
                continue;
            }
            for (int y = segment.surfaceY() + 1; y <= segment.surfaceY() + headroom; y++) {
                spaces.add(new Spot(road.world(), entry.getKey().x(), y, entry.getKey().z()));
            }
        }
        return spaces;
    }

    /**
     * Every column the road actually covers, and which step of it each belongs to.
     *
     * <p><strong>From the footprint, not from the cross-sections.</strong> A cross-section is a line
     * of columns at right angles to one step; two consecutive ones on a diagonal do not touch, and
     * every gap between them is a column that is inside the road and not in any cross-section. Under
     * the sea that is not cosmetic: the shell is built against every face of the cleared space, so
     * each of those gaps became a pane of glass standing across the passage — a lattice through the
     * middle of the tunnel, which is exactly what it looked like.
     *
     * <p>{@code Polyline#footprint} is contiguous by construction, so built from that the cleared
     * space has no holes in it and the shell has nothing to fill.
     */
    private Map<Column, RoadSegment> columnsAlong(RoadPath road, List<RoadSegment> plan) {
        Map<Column, RoadSegment> byColumn = new LinkedHashMap<>();
        if (plan.isEmpty()) {
            return byColumn;
        }
        for (Column column : road.path().footprint(road.width())) {
            RoadSegment nearest = plan.get(0);
            long closest = Long.MAX_VALUE;
            for (RoadSegment segment : plan) {
                long dx = segment.column().x() - column.x();
                long dz = segment.column().z() - column.z();
                long distance = dx * dx + dz * dz;
                if (distance < closest) {
                    closest = distance;
                    nearest = segment;
                }
            }
            byColumn.put(column, nearest);
        }
        return byColumn;
    }

    /**
     * The shell around an enclosed stretch: every face of the hollow that is not more hollow.
     *
     * <p>Derived from the interior rather than drawn as walls-and-a-ceiling, which is the difference
     * between a tube that holds the sea back and one that holds it back on the straights. A bend
     * joins two cross-sections at an angle, and the outside of that join is a face a wall-and-ceiling
     * rule never sees.
     *
     * <p>The road surface is not in the interior, so the floor is sealed by its own paving — except
     * where a cross-section is wider than the one before it, which this catches like any other face.
     */
    /** Whether this face would let water or air in if it were left as it is. */
    private boolean needsSealing(Ground ground, Spot face) {
        String there = ground.materialAt(face);
        if (there == null) {
            return false;
        }
        return terrain.isClearable(there);
    }

    /** Every block of road surface along the route — a solid floor already, and not to be walled over. */
    public Set<Spot> surfaceOf(RoadPath road, List<RoadSegment> plan) {
        Set<Spot> surface = new LinkedHashSet<>();
        for (Map.Entry<Column, RoadSegment> entry : columnsAlong(road, plan).entrySet()) {
            surface.add(new Spot(road.world(), entry.getKey().x(),
                    entry.getValue().surfaceY(), entry.getKey().z()));
        }
        return surface;
    }

    private static List<Spot> faces(Spot spot) {
        return List.of(spot.offset(1, 0, 0), spot.offset(-1, 0, 0), spot.offset(0, 1, 0),
                spot.offset(0, -1, 0), spot.offset(0, 0, 1), spot.offset(0, 0, -1));
    }



    private void edge(List<BatchBuilder.Placement> placements, String world, Column left, Column right,
                      int y, org.bukkit.Material material) {
        if (material == null) {
            return;
        }
        placements.add(place(world, left, y, material.name()));
        placements.add(place(world, right, y, material.name()));
    }

    /**
     * A pier from under the deck down to whatever is below it.
     *
     * <p>Built from the wood growing in that biome when the profile asks for it: a trestle over a
     * mangrove swamp made of oak looks imported, and one made of mangrove looks like something the
     * people who live there put up.
     */
    private void pier(List<BatchBuilder.Placement> placements, RoadPath road, RoadSegment segment,
                      RoadProfile profile, Ground ground) {
        int from = segment.surfaceY() - 1;
        int to = Math.max(segment.reading().groundY() - 1, from - MAX_PIER_DEPTH);
        String material = supportMaterial(road, segment, profile, ground);
        for (int y = from; y >= to; y--) {
            placements.add(place(road.world(), segment.column(), y, material));
        }
    }

    /** What a pier or a lamp post is made of here. */
    private String supportMaterial(RoadPath road, RoadSegment segment, RoadProfile profile, Ground ground) {
        if (!profile.woodenSupports() || ground == null) {
            return profile.support().name();
        }
        String biome = ground.biomeAt(new Spot(road.world(), segment.column().x(),
                segment.surfaceY(), segment.column().z()));
        return BiomeWood.logFor(biome);
    }

    /**
     * Light along the road.
     *
     * <p>An enclosed stretch is lit from its own ceiling — a lamp post inside a tunnel bore is a
     * post in the middle of the road. Everything else gets a post and a lamp alongside, alternating
     * sides so a narrow road is not walled in by its own lighting.
     */
    private void lamps(List<BatchBuilder.Placement> placements, RoadPath road, RoadSegment segment,
                       int index, Column left, Column right, int surfaceY, RoadProfile profile,
                       Ground ground) {
        boolean enclosed = segment.kind().isEnclosed();
        if (!enclosed && !profile.isLit()) {
            return;
        }
        if (index % profile.lampSpacing() != 0) {
            return;
        }
        if (enclosed) {
            placements.add(place(road.world(), segment.column(), surfaceY + profile.headroom(),
                    profile.tunnelLight().name()));
            return;
        }
        Column side = (index / profile.lampSpacing()) % 2 == 0 ? left : right;
        int dx = Integer.signum(side.x() - segment.column().x());
        int dz = Integer.signum(side.z() - segment.column().z());
        Column beside = side.offset(dx, dz);
        String post = profile.woodenSupports() && ground != null
                ? BiomeWood.logFor(ground.biomeAt(new Spot(road.world(), beside.x(), surfaceY, beside.z())))
                : profile.lampPost().name();
        placements.add(place(road.world(), beside, surfaceY,
                PavingPalette.forMaterial(road.material()).at(beside)));
        placements.add(place(road.world(), beside, surfaceY + 1, post));
        placements.add(place(road.world(), beside, surfaceY + 2, post));
        placements.add(place(road.world(), beside, surfaceY + 3, profile.lamp().name()));
    }

    private static BatchBuilder.Placement place(String world, Column column, int y, String material) {
        return new BatchBuilder.Placement(new Spot(world, column.x(), y, column.z()), material);
    }
}
