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

        // Paved with the gravel family rather than with gravel: a road of one block reads as a
        // texture stretched over the ground.
        for (int z = -2; z <= 2; z++) {
            assertThat(result.get(new Spot(WORLD, 10, 71, z)))
                    .isIn("GRAVEL", "COBBLESTONE", "STONE", "ANDESITE");
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

        // On the surface, not standing on it: a kerb is the edge of the road, and a course above it
        // is a wall along a footpath.
        assertThat(result.get(new Spot(WORLD, 10, 71, -2))).isEqualTo("COBBLESTONE_SLAB");
        assertThat(result.get(new Spot(WORLD, 10, 71, 2))).isEqualTo("COBBLESTONE_SLAB");
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
        // The post is the wood of the biome it stands in; a world that cannot say is oak.
        assertThat(result.values()).contains("OAK_LOG");
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

        // Timber trestles: the wood growing in that biome, which for a world that cannot say is oak.
        long pierBlocks = result.entrySet().stream()
                .filter(entry -> "OAK_LOG".equals(entry.getValue()))
                .filter(entry -> entry.getKey().y() < 70 && entry.getKey().y() > 40)
                .count();
        assertThat(pierBlocks).isPositive();

        // A pier reaches the ground it stands on rather than stopping in mid-air.
        int lowestPier = result.entrySet().stream()
                .filter(entry -> "OAK_LOG".equals(entry.getValue()))
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

        // Floored in something you can see past, not in the road's own gravel.
        assertThat(result.get(new Spot(WORLD, 15, tunnelY, 0))).isEqualTo("SMOOTH_STONE");
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
    @DisplayName("a sea tunnel that runs diagonally is watertight too")
    void leavesNoWaterInADiagonalTunnel() {
        RoadPath path = new RoadPath("road-d", "Diagonal", UUID.randomUUID(), WORLD,
                new Polyline(List.of(new Column(0, 0), new Column(160, 160))),
                5, Material.GRAVEL, ElevationMode.FOLLOW_TERRAIN, 64, RoadProfile.plain());

        FakeGround ground = new FakeGround().fillWith("AIR");
        for (int x = -10; x <= 170; x++) {
            for (int z = -10; z <= 170; z++) {
                boolean atSea = x >= 20 && x <= 140;
                int top = atSea ? 35 : 70;
                for (int y = -64; y <= top; y++) {
                    ground.put(new Spot(WORLD, x, y, z), "STONE");
                }
                if (atSea) {
                    for (int y = 36; y <= 62; y++) {
                        ground.put(new Spot(WORLD, x, y, z), "WATER");
                    }
                }
            }
        }

        List<RoadSegment> plan = profiler.profile(path, ground, rules);
        for (BatchBuilder.Placement placement : builder.placements(path, plan, ground)) {
            ground.set(placement.spot(), placement.material());
        }

        // Every enclosed step: the air a player would be standing in must have stayed air.
        for (RoadSegment segment : plan) {
            if (segment.kind() != de.raindancer.modules.wallsroads.model.SegmentKind.GLASS_TUNNEL) {
                continue;
            }
            int index = plan.indexOf(segment);
            for (Column across : builder.crossSection(plan, index, path.width())) {
                for (int y = segment.surfaceY() + 1; y <= segment.surfaceY() + 3; y++) {
                    Spot inside = new Spot(WORLD, across.x(), y, across.z());
                    assertThat(ground.materialAt(inside))
                            .as("water reached %s, which is a tunnel that drowns whoever is in it", inside)
                            .isNotEqualTo("WATER");
                }
            }
        }
    }

    @Test
    @DisplayName("every face of an enclosed stretch is either more tunnel or a placed block")
    void theShellHasNoHolesInIt() {
        RoadPath path = new RoadPath("road-d", "Diagonal", UUID.randomUUID(), WORLD,
                new Polyline(List.of(new Column(0, 0), new Column(160, 160))),
                5, Material.GRAVEL, ElevationMode.FOLLOW_TERRAIN, 64, RoadProfile.plain());

        FakeGround ground = new FakeGround().fillWith("AIR");
        for (int x = -10; x <= 170; x++) {
            for (int z = -10; z <= 170; z++) {
                boolean atSea = x >= 20 && x <= 140;
                int top = atSea ? 35 : 70;
                for (int y = -64; y <= top; y++) {
                    ground.put(new Spot(WORLD, x, y, z), "STONE");
                }
                if (atSea) {
                    for (int y = 36; y <= 62; y++) {
                        ground.put(new Spot(WORLD, x, y, z), "WATER");
                    }
                }
            }
        }

        List<RoadSegment> plan = profiler.profile(path, ground, rules);
        List<BatchBuilder.Placement> placements = builder.placements(path, plan, ground);
        Map<Spot, String> result = resultOf(placements);

        // The property a static test can check and a running server cannot be asked politely about:
        // water flows on the next tick, so a shell with one hole in it floods overnight.
        java.util.Set<Spot> interior = builder.interiorOf(path, plan);
        assertThat(interior).isNotEmpty();
        for (Spot inside : interior) {
            for (Spot neighbour : List.of(inside.offset(1, 0, 0), inside.offset(-1, 0, 0),
                    inside.offset(0, 1, 0), inside.offset(0, -1, 0),
                    inside.offset(0, 0, 1), inside.offset(0, 0, -1))) {
                if (interior.contains(neighbour)) {
                    continue;
                }
                // Either the shell put a block there, or the world already had something solid —
                // a face against the sea bed is rock, and glazing it walls the tunnel off from the
                // one thing worth looking at through glass.
                String placed = result.get(neighbour);
                boolean sealed = placed != null && !placed.equals("AIR");
                boolean alreadySolid = !new de.raindancer.modules.wallsroads.service.TerrainReader()
                        .isClearable(ground.materialAt(neighbour));
                assertThat(sealed || alreadySolid)
                        .as("%s lets the sea into the tunnel", neighbour)
                        .isTrue();
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
