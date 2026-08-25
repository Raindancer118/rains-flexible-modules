package de.raindancer.modules.wallsroads.service;

import de.raindancer.core.world.geometry.ColumnPolygon.Column;
import de.raindancer.core.world.geometry.Polyline;
import de.raindancer.core.world.safety.Spot;
import de.raindancer.modules.wallsroads.model.ElevationMode;
import de.raindancer.modules.wallsroads.model.RoadPath;
import de.raindancer.modules.wallsroads.model.RoadProfile;
import de.raindancer.modules.wallsroads.model.RoadSegment;
import de.raindancer.modules.wallsroads.model.SegmentKind;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a road decides to cross what is in its way — the whole difference between a road that follows
 * the land and one that is a stripe of gravel draped over it.
 */
class RouteProfilerTest {

    private static final String WORLD = "world";

    private final RouteProfiler profiler = new RouteProfiler();
    private final RouteProfiler.Rules rules = RouteProfiler.Rules.DEFAULTS;

    private static RoadPath road(int fromX, int toX) {
        return new RoadPath("road-1", "Test Road", UUID.randomUUID(), WORLD,
                new Polyline(List.of(new Column(fromX, 0), new Column(toX, 0))),
                3, Material.GRAVEL, ElevationMode.FOLLOW_TERRAIN, 64, RoadProfile.plain());
    }

    /** A world filled with stone up to a height that depends on x. */
    private static FakeGround terrain(java.util.function.IntUnaryOperator heightAtX, int fromX, int toX) {
        FakeGround ground = new FakeGround().fillWith("AIR");
        for (int x = fromX - 4; x <= toX + 4; x++) {
            int top = heightAtX.applyAsInt(x);
            for (int y = -64; y <= top; y++) {
                ground.put(new Spot(WORLD, x, y, 0), "STONE");
            }
        }
        return ground;
    }

    private static void flood(FakeGround ground, int fromX, int toX, int fromY, int toY) {
        for (int x = fromX; x <= toX; x++) {
            for (int y = fromY; y <= toY; y++) {
                ground.put(new Spot(WORLD, x, y, 0), "WATER");
            }
        }
    }

    @Test
    @DisplayName("flat land is simply followed, with no bridge or tunnel anywhere")
    void flatLandIsJustRoad() {
        List<RoadSegment> plan = profiler.profile(road(0, 30), terrain(x -> 70, 0, 30), rules);

        assertThat(plan).isNotEmpty();
        assertThat(plan).allSatisfy(segment -> {
            assertThat(segment.kind()).isEqualTo(SegmentKind.GROUND);
            assertThat(segment.surfaceY()).isEqualTo(71);
        });
    }

    @Test
    @DisplayName("a ravine is bridged, and the deck stays level across it rather than diving in")
    void bridgesARavine() {
        FakeGround ground = terrain(x -> x >= 10 && x <= 20 ? 40 : 70, 0, 30);

        List<RoadSegment> plan = profiler.profile(road(0, 30), ground, rules);

        List<RoadSegment> overTheGap = plan.stream().filter(s -> s.column().x() > 11 && s.column().x() < 19).toList();
        assertThat(overTheGap).isNotEmpty();
        assertThat(overTheGap).allSatisfy(segment -> {
            assertThat(segment.kind()).isEqualTo(SegmentKind.BRIDGE);
            assertThat(segment.surfaceY()).isEqualTo(71);
        });
    }

    @Test
    @DisplayName("a hill is tunnelled through rather than climbed over")
    void tunnelsThroughAHill() {
        FakeGround ground = terrain(x -> x >= 10 && x <= 20 ? 95 : 70, 0, 30);

        List<RoadSegment> plan = profiler.profile(road(0, 30), ground, rules);

        List<RoadSegment> insideTheHill = plan.stream().filter(s -> s.column().x() > 12 && s.column().x() < 18).toList();
        assertThat(insideTheHill).isNotEmpty();
        assertThat(insideTheHill).allSatisfy(segment ->
                assertThat(segment.kind()).isEqualTo(SegmentKind.TUNNEL));
    }

    @Test
    @DisplayName("a stream is bridged, not tunnelled — a glass tube under a brook is absurd")
    void bridgesAShortCrossing() {
        FakeGround ground = terrain(x -> x >= 14 && x <= 19 ? 60 : 70, 0, 30);
        flood(ground, 14, 19, 61, 70);

        List<RoadSegment> plan = profiler.profile(road(0, 30), ground, rules);

        assertThat(plan).noneMatch(segment -> segment.kind() == SegmentKind.GLASS_TUNNEL);
        assertThat(plan).anyMatch(segment -> segment.kind() == SegmentKind.BRIDGE);
    }

    @Test
    @DisplayName("a long deep crossing goes under it, in a glass tunnel on the sea bed")
    void tunnelsUnderAnOcean() {
        FakeGround ground = terrain(x -> x >= 20 && x <= 140 ? 35 : 70, 0, 160);
        flood(ground, 20, 140, 36, 62);

        List<RoadSegment> plan = profiler.profile(road(0, 160), ground, rules);

        List<RoadSegment> midOcean = plan.stream().filter(s -> s.column().x() > 70 && s.column().x() < 90).toList();
        assertThat(midOcean).isNotEmpty();
        assertThat(midOcean).allSatisfy(segment -> {
            assertThat(segment.kind()).isEqualTo(SegmentKind.GLASS_TUNNEL);
            // On the bed, well under the surface — this is a tunnel, not a bridge that got wet.
            assertThat(segment.surfaceY()).isLessThan(50);
        });
    }

    @Test
    @DisplayName("the descent into an ocean tunnel is a ramp, not a cliff")
    void rampsIntoTheTunnel() {
        FakeGround ground = terrain(x -> x >= 20 && x <= 140 ? 35 : 70, 0, 160);
        flood(ground, 20, 140, 36, 62);

        List<RoadSegment> plan = profiler.profile(road(0, 160), ground, rules);

        for (int i = 1; i < plan.size(); i++) {
            int step = Math.abs(plan.get(i).surfaceY() - plan.get(i - 1).surfaceY());
            assertThat(step).isLessThanOrEqualTo(rules.maxGrade());
        }
    }

    @Test
    @DisplayName("a cliff is climbed at the allowed grade and no faster")
    void limitsTheGrade() {
        FakeGround ground = terrain(x -> x < 15 ? 70 : 110, 0, 40);

        List<RoadSegment> plan = profiler.profile(road(0, 40), ground, rules);

        for (int i = 1; i < plan.size(); i++) {
            assertThat(Math.abs(plan.get(i).surfaceY() - plan.get(i - 1).surfaceY()))
                    .isLessThanOrEqualTo(rules.maxGrade());
        }
    }

    @Test
    @DisplayName("a fixed-height road keeps its height, and is a bridge wherever the ground falls away")
    void fixedHeightStillClassifies() {
        RoadPath fixed = new RoadPath("road-2", "Causeway", UUID.randomUUID(), WORLD,
                new Polyline(List.of(new Column(0, 0), new Column(30, 0))),
                3, Material.GRAVEL, ElevationMode.FIXED_Y, 80, RoadProfile.plain());
        FakeGround ground = terrain(x -> 70, 0, 30);

        List<RoadSegment> plan = profiler.profile(fixed, ground, rules);

        assertThat(plan).allSatisfy(segment -> {
            assertThat(segment.surfaceY()).isEqualTo(80);
            assertThat(segment.kind()).isEqualTo(SegmentKind.BRIDGE);
        });
    }

    @Test
    @DisplayName("every column of the road gets exactly one segment, in order along it")
    void coversThePathInOrder() {
        RoadPath path = road(0, 25);

        List<RoadSegment> plan = profiler.profile(path, terrain(x -> 70, 0, 25), rules);

        assertThat(plan).extracting(RoadSegment::column)
                .containsExactlyElementsOf(path.path().orderedColumns());
    }
}
