package de.raindancer.modules.wallsroads.service;

import de.raindancer.core.world.build.BatchBuilder;
import de.raindancer.core.world.build.Ground;
import de.raindancer.core.world.geometry.ColumnPolygon.Column;
import de.raindancer.core.world.safety.Spot;
import de.raindancer.modules.wallsroads.model.RoadPath;
import de.raindancer.modules.wallsroads.model.RoadProfile;
import de.raindancer.modules.wallsroads.model.RoadSegment;
import de.raindancer.modules.wallsroads.model.SegmentKind;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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

    /** Blocks between a bridge's piers. Closer looks like a wall, further looks unsupported. */
    private static final int PIER_SPACING = 8;

    /** How far down a pier will reach before it is left as a stub — a pier into the void is nothing. */
    private static final int MAX_PIER_DEPTH = 64;

    public List<BatchBuilder.Placement> placements(RoadPath road, List<RoadSegment> plan, Ground ground) {
        List<BatchBuilder.Placement> placements = new ArrayList<>();
        RoadProfile profile = road.profile();
        String paving = road.material().name();

        // Worked out over the whole route before anything is placed, because a shell is only sound if
        // it is built against every face of the *finished* space. Segment by segment, the join between
        // two cross-sections on a bend is a face nobody lined — and one missing block under the sea is
        // a tunnel that floods on the next tick.
        Set<Spot> interior = interiorOf(road, plan);
        // And the whole road surface, for the same reason: a bend's lining would otherwise wall in
        // the paving laid by the segment before it, because that block is not in *this* cross-section.
        Set<Spot> surface = surfaceOf(road, plan);

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

            clear(placements, road.world(), cross, surfaceY, headroom, segment.kind());

            for (Column column : cross) {
                placements.add(place(road.world(), column, surfaceY, paving));
            }

            switch (segment.kind()) {
                case BRIDGE -> {
                    edge(placements, road.world(), leftEdge, rightEdge, surfaceY + 1, profile.railing());
                    if (index % PIER_SPACING == 0) {
                        pier(placements, road.world(), segment, profile);
                    }
                }
                case TUNNEL, GLASS_TUNNEL -> line(placements, road.world(), interior, surface, cross,
                        surfaceY, headroom,
                        segment.kind() == SegmentKind.TUNNEL
                                ? profile.tunnelLining().name() : profile.glass().name());
                case GROUND -> {
                    if (profile.hasKerb()) {
                        edge(placements, road.world(), leftEdge, rightEdge, surfaceY + 1, profile.kerb());
                    }
                }
                default -> {
                }
            }

            lamps(placements, road, segment, index, leftEdge, rightEdge, surfaceY, profile);
        }
        return placements;
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
     * face of this set is either more of the set or a block that was placed.
     */
    public Set<Spot> interiorOf(RoadPath road, List<RoadSegment> plan) {
        Set<Spot> interior = new LinkedHashSet<>();
        int headroom = road.profile().headroom();
        for (int index = 0; index < plan.size(); index++) {
            RoadSegment segment = plan.get(index);
            if (!segment.kind().isEnclosed()) {
                continue;
            }
            for (Column column : crossSection(plan, index, road.width())) {
                for (int y = segment.surfaceY() + 1; y <= segment.surfaceY() + headroom; y++) {
                    interior.add(new Spot(road.world(), column.x(), y, column.z()));
                }
            }
        }
        return interior;
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
    private void line(List<BatchBuilder.Placement> placements, String world, Set<Spot> interior,
                      Set<Spot> surface, List<Column> cross, int surfaceY, int headroom,
                      String material) {
        for (Column column : cross) {
            for (int y = surfaceY + 1; y <= surfaceY + headroom; y++) {
                Spot inside = new Spot(world, column.x(), y, column.z());
                for (Spot face : faces(inside)) {
                    if (interior.contains(face) || surface.contains(face)) {
                        continue;
                    }
                    placements.add(new BatchBuilder.Placement(face, material));
                }
            }
        }
    }

    /** Every block of road surface along the route — a solid floor already, and not to be walled over. */
    public Set<Spot> surfaceOf(RoadPath road, List<RoadSegment> plan) {
        Set<Spot> surface = new LinkedHashSet<>();
        for (int index = 0; index < plan.size(); index++) {
            RoadSegment segment = plan.get(index);
            for (Column column : crossSection(plan, index, road.width())) {
                surface.add(new Spot(road.world(), column.x(), segment.surfaceY(), column.z()));
            }
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

    /** A pier from under the deck down to whatever is below it. */
    private void pier(List<BatchBuilder.Placement> placements, String world, RoadSegment segment,
                      RoadProfile profile) {
        int from = segment.surfaceY() - 1;
        int to = Math.max(segment.reading().groundY() - 1, from - MAX_PIER_DEPTH);
        String material = profile.support().name();
        for (int y = from; y >= to; y--) {
            placements.add(place(world, segment.column(), y, material));
        }
    }

    /**
     * Light along the road.
     *
     * <p>An enclosed stretch is lit from its own ceiling — a lamp post inside a tunnel bore is a
     * post in the middle of the road. Everything else gets a post and a lamp alongside, alternating
     * sides so a narrow road is not walled in by its own lighting.
     */
    private void lamps(List<BatchBuilder.Placement> placements, RoadPath road, RoadSegment segment,
                       int index, Column left, Column right, int surfaceY, RoadProfile profile) {
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
        placements.add(place(road.world(), beside, surfaceY, road.material().name()));
        placements.add(place(road.world(), beside, surfaceY + 1, profile.lampPost().name()));
        placements.add(place(road.world(), beside, surfaceY + 2, profile.lampPost().name()));
        placements.add(place(road.world(), beside, surfaceY + 3, profile.lamp().name()));
    }

    private static BatchBuilder.Placement place(String world, Column column, int y, String material) {
        return new BatchBuilder.Placement(new Spot(world, column.x(), y, column.z()), material);
    }
}
