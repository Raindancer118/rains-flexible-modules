package de.raindancer.modules.wallsroads.service;

import de.raindancer.core.world.build.BatchBuilder;
import de.raindancer.core.world.geometry.ColumnPolygon.Column;
import de.raindancer.core.world.geometry.Polyline;
import de.raindancer.core.world.safety.Spot;
import de.raindancer.modules.wallsroads.model.ElevationMode;
import de.raindancer.modules.wallsroads.model.RoadPath;
import de.raindancer.modules.wallsroads.model.RoadProfile;
import de.raindancer.modules.wallsroads.model.RoadSegment;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntUnaryOperator;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Turning a profiled route into blocks: paving, edging, light, and whatever carries or encloses it. */
class RoadBuilderTest {

    private static final String WORLD = "world";

    private final RouteProfiler profiler = new RouteProfiler();
    private final RoadBuilder builder = new RoadBuilder();
    private final RouteProfiler.Rules rules = RouteProfiler.Rules.DEFAULTS;

    private static RoadPath road(int toX, double width, RoadProfile profile) {
        return new RoadPath("road-1", "Test Road", UUID.randomUUID(), WORLD,
                new Polyline(List.of(new Column(0, 0), new Column(toX, 0))),
                width, Material.GRAVEL, ElevationMode.FOLLOW_TERRAIN, 64, profile);
    }

    private static FakeGround terrain(IntUnaryOperator heightAtX, int fromX, int toX) {
        FakeGround ground = new FakeGround().fillWith("AIR");
        for (int x = fromX - 8; x <= toX + 8; x++) {
            for (int z = -8; z <= 8; z++) {
                int top = heightAtX.applyAsInt(x);
                for (int y = -64; y <= top; y++) {
                    ground.put(new Spot(WORLD, x, y, z), "STONE");
                }
            }
        }
        return ground;
    }

    private static void flood(FakeGround ground, int fromX, int toX, int fromY, int toY) {
        for (int x = fromX; x <= toX; x++) {
            for (int z = -8; z <= 8; z++) {
                for (int y = fromY; y <= toY; y++) {
                    ground.put(new Spot(WORLD, x, y, z), "WATER");
                }
            }
        }
    }

    /** What the queue would leave behind, as a map — the world after the build, without a world. */
    private static Map<Spot, String> resultOf(List<BatchBuilder.Placement> placements) {
        return placements.stream().collect(Collectors.toMap(BatchBuilder.Placement::spot,
                BatchBuilder.Placement::material, (first, second) -> second));
    }

    /** What the world reads as everywhere the build could plausibly have reached. */
    private static Map<Spot, String> scan(FakeGround ground) {
        Map<Spot, String> seen = new java.util.HashMap<>();
        for (int x = -4; x <= 34; x++) {
            for (int z = -6; z <= 6; z++) {
                for (int y = 30; y <= 90; y++) {
                    Spot spot = new Spot(WORLD, x, y, z);
                    seen.put(spot, ground.materialAt(spot));
                }
            }
        }
        return seen;
    }

    @Test
    @DisplayName("a flat road is paved across its whole width, at the height the profile decided")
    void pavesTheFullWidth() {
        RoadPath path = road(20, 5, RoadProfile.plain());
        FakeGround ground = terrain(x -> 70, 0, 20);
        List<RoadSegment> plan = profiler.profile(path, ground, rules);

        Map<Spot, String> result = resultOf(builder.placements(path, plan, ground));

        for (int z = -2; z <= 2; z++) {
            assertThat(result.get(new Spot(WORLD, 10, 71, z))).isEqualTo("GRAVEL");
        }
        assertThat(result.get(new Spot(WORLD, 10, 71, 3))).isNull();
    }

    @Test
    @DisplayName("the road is cleared overhead, so one laid through a slope can actually be walked")
    void clearsHeadroom() {
        RoadPath path = road(20, 3, RoadProfile.plain());
        FakeGround ground = terrain(x -> 70, 0, 20);
        List<RoadSegment> plan = profiler.profile(path, ground, rules);

        Map<Spot, String> result = resultOf(builder.placements(path, plan, ground));

        assertThat(result.get(new Spot(WORLD, 10, 72, 0))).isEqualTo("AIR");
        assertThat(result.get(new Spot(WORLD, 10, 74, 0))).isEqualTo("AIR");
    }

    @Test
    @DisplayName("a kerb runs along both edges and nowhere in the middle")
    void kerbsTheEdges() {
        RoadPath path = road(20, 5, RoadProfile.lit());
        FakeGround ground = terrain(x -> 70, 0, 20);
        List<RoadSegment> plan = profiler.profile(path, ground, rules);

        Map<Spot, String> result = resultOf(builder.placements(path, plan, ground));

        assertThat(result.get(new Spot(WORLD, 10, 72, -2))).isEqualTo("STONE_BRICK_SLAB");
        assertThat(result.get(new Spot(WORLD, 10, 72, 2))).isEqualTo("STONE_BRICK_SLAB");
        assertThat(result.get(new Spot(WORLD, 10, 72, 0))).isEqualTo("AIR");
    }

    @Test
    @DisplayName("a plain road has no kerb and no lamps at all")
    void plainRoadIsJustPaving() {
        RoadPath path = road(40, 5, RoadProfile.plain());
        FakeGround ground = terrain(x -> 70, 0, 40);
        List<RoadSegment> plan = profiler.profile(path, ground, rules);

        Map<Spot, String> result = resultOf(builder.placements(path, plan, ground));

        assertThat(result.values()).doesNotContain("LANTERN", "STONE_BRICK_SLAB", "OAK_FENCE");
    }

    @Test
    @DisplayName("lamps appear along a lit road, spaced as the profile says")
    void lightsALitRoad() {
        RoadProfile profile = RoadProfile.lit();
        RoadPath path = road(60, 5, profile);
        FakeGround ground = terrain(x -> 70, 0, 60);
        List<RoadSegment> plan = profiler.profile(path, ground, rules);

        Map<Spot, String> result = resultOf(builder.placements(path, plan, ground));

        long lamps = result.values().stream().filter("LANTERN"::equals).count();
        assertThat(lamps).isBetween(4L, 14L);
        assertThat(result.values()).contains("OAK_FENCE");
    }

    @Test
    @DisplayName("a bridge gets a railing on both sides, so nothing walks off the edge of it")
    void railsABridge() {
        RoadPath path = road(30, 5, RoadProfile.plain());
        FakeGround ground = terrain(x -> x >= 10 && x <= 20 ? 40 : 70, 0, 30);
        List<RoadSegment> plan = profiler.profile(path, ground, rules);

        Map<Spot, String> result = resultOf(builder.placements(path, plan, ground));

        assertThat(result.get(new Spot(WORLD, 15, 72, -2))).isEqualTo("OAK_FENCE");
        assertThat(result.get(new Spot(WORLD, 15, 72, 2))).isEqualTo("OAK_FENCE");
    }

    @Test
    @DisplayName("a bridge stands on piers that reach the ground, not on nothing")
    void supportsABridge() {
        RoadPath path = road(30, 5, RoadProfile.plain());
        FakeGround ground = terrain(x -> x >= 10 && x <= 20 ? 40 : 70, 0, 30);
        List<RoadSegment> plan = profiler.profile(path, ground, rules);

        Map<Spot, String> result = resultOf(builder.placements(path, plan, ground));

        long pierBlocks = result.entrySet().stream()
                .filter(entry -> "COBBLESTONE".equals(entry.getValue()))
                .filter(entry -> entry.getKey().y() < 70 && entry.getKey().y() > 40)
                .count();
        assertThat(pierBlocks).isPositive();

        // A pier reaches the ground it stands on rather than stopping in mid-air.
        int lowestPier = result.entrySet().stream()
                .filter(entry -> "COBBLESTONE".equals(entry.getValue()))
                .mapToInt(entry -> entry.getKey().y()).min().orElseThrow();
        assertThat(lowestPier).isLessThanOrEqualTo(42);
    }

    @Test
    @DisplayName("a tunnel is bored clear and lined, and lit — otherwise it is a hole in a hill")
    void boresATunnel() {
        RoadPath path = road(30, 5, RoadProfile.plain());
        FakeGround ground = terrain(x -> x >= 10 && x <= 20 ? 95 : 70, 0, 30);
        List<RoadSegment> plan = profiler.profile(path, ground, rules);
        int tunnelY = plan.stream().filter(s -> s.column().x() == 15).findFirst().orElseThrow().surfaceY();

        Map<Spot, String> result = resultOf(builder.placements(path, plan, ground));

        assertThat(result.get(new Spot(WORLD, 15, tunnelY, 0))).isEqualTo("GRAVEL");
        assertThat(result.get(new Spot(WORLD, 15, tunnelY + 1, 0))).isEqualTo("AIR");
        assertThat(result.get(new Spot(WORLD, 15, tunnelY + 2, 0))).isEqualTo("AIR");
        // Lined overhead, so the hill above does not fall into it.
        assertThat(result.get(new Spot(WORLD, 15, tunnelY + 5, 0))).isEqualTo("STONE_BRICKS");
        assertThat(result.values()).contains("LANTERN");
    }

    @Test
    @DisplayName("an ocean tunnel is a glass tube with air in it, not a road with water on top")
    void encasesAnOceanTunnel() {
        RoadPath path = road(160, 5, RoadProfile.plain());
        FakeGround ground = terrain(x -> x >= 20 && x <= 140 ? 35 : 70, 0, 160);
        flood(ground, 20, 140, 36, 62);
        List<RoadSegment> plan = profiler.profile(path, ground, rules);
        RoadSegment mid = plan.stream().filter(s -> s.column().x() == 80).findFirst().orElseThrow();

        Map<Spot, String> result = resultOf(builder.placements(path, plan, ground));

        assertThat(result.get(new Spot(WORLD, 80, mid.surfaceY() + 1, 0))).isEqualTo("AIR");
        assertThat(result.get(new Spot(WORLD, 80, mid.surfaceY() + 1, 3))).isEqualTo("GLASS");
        assertThat(result.get(new Spot(WORLD, 80, mid.surfaceY() + 5, 0))).isEqualTo("GLASS");
    }

    @Test
    @DisplayName("nothing inside a finished sea tunnel is left as water")
    void leavesNoWaterInTheTunnel() {
        RoadPath path = road(160, 5, RoadProfile.plain());
        FakeGround ground = terrain(x -> x >= 20 && x <= 140 ? 35 : 70, 0, 160);
        flood(ground, 20, 140, 36, 62);
        List<RoadSegment> plan = profiler.profile(path, ground, rules);
        RoadSegment mid = plan.stream().filter(s -> s.column().x() == 80).findFirst().orElseThrow();

        List<BatchBuilder.Placement> placements = builder.placements(path, plan, ground);
        for (BatchBuilder.Placement placement : placements) {
            ground.set(placement.spot(), placement.material());
        }

        for (int y = mid.surfaceY() + 1; y <= mid.surfaceY() + 4; y++) {
            for (int z = -2; z <= 2; z++) {
                assertThat(ground.materialAt(new Spot(WORLD, 80, y, z))).isNotEqualTo("WATER");
            }
        }
    }

    @Test
    @DisplayName("building then tearing down leaves the world exactly as it was found")
    void teardownRestoresEverything() {
        RoadPath path = road(30, 5, RoadProfile.lit());
        FakeGround ground = terrain(x -> x >= 10 && x <= 20 ? 40 : 70, 0, 30);
        Map<Spot, String> before = scan(ground);
        List<RoadSegment> plan = profiler.profile(path, ground, rules);

        BatchBuilder build = new BatchBuilder(ground, builder.placements(path, plan, ground));
        build.advance(build.total());
        BatchBuilder undo = new BatchBuilder(ground, build.snapshotSoFar().asRestorePlacements());
        undo.advance(undo.total());

        assertThat(scan(ground)).isEqualTo(before);
    }
}
