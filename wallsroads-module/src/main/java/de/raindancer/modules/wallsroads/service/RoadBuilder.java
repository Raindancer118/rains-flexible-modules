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
                case TUNNEL -> line(placements, road.world(), cross, surfaceY, headroom,
                        profile.tunnelLining().name());
                case GLASS_TUNNEL -> line(placements, road.world(), cross, surfaceY, headroom,
                        profile.glass().name());
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
     * The shell around an enclosed stretch: walls either side, and a ceiling over it.
     *
     * <p>Placed after the bore is cleared, and only <em>outside</em> the cleared columns, so the
     * lining never lands in the space somebody is supposed to ride through.
     */
    private void line(List<BatchBuilder.Placement> placements, String world, List<Column> cross,
                      int surfaceY, int headroom, String material) {
        Column left = cross.get(0);
        Column right = cross.get(cross.size() - 1);
        int dx = Integer.signum(right.x() - left.x());
        int dz = Integer.signum(right.z() - left.z());
        Column outsideLeft = left.offset(-dx, -dz);
        Column outsideRight = right.offset(dx, dz);

        for (int y = surfaceY; y <= surfaceY + headroom; y++) {
            placements.add(place(world, outsideLeft, y, material));
            placements.add(place(world, outsideRight, y, material));
        }
        int ceilingY = surfaceY + headroom + 1;
        for (Column column : cross) {
            placements.add(place(world, column, ceilingY, material));
        }
        placements.add(place(world, outsideLeft, ceilingY, material));
        placements.add(place(world, outsideRight, ceilingY, material));
        // The floor under an underwater tube: the sea bed is not a floor anybody would trust.
        for (Column column : List.of(outsideLeft, outsideRight)) {
            placements.add(place(world, column, surfaceY - 1, material));
        }
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
