package de.raindancer.modules.wallsroads.service;

import de.raindancer.core.world.build.BatchBuilder;
import de.raindancer.core.world.geometry.ColumnPolygon;
import de.raindancer.core.world.safety.Spot;
import de.raindancer.modules.wallsroads.model.CornerStyle;
import de.raindancer.modules.wallsroads.model.Wall;
import de.raindancer.modules.wallsroads.model.WallProfile;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntUnaryOperator;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A wall that reads as a castle wall rather than as a ribbon of stone laid across the landscape.
 *
 * <p>The screenshot that prompted all of this: a wall built at one height, slicing through every hill
 * it met and floating over every dip, two blocks of it visible and nothing to stand on.
 */
class CastleWallTest {

    private static final String WORLD = "world";

    private final WallBuildService builder = new WallBuildService();

    private static Wall wall(WallProfile profile, int height, int thickness) {
        Wall wall = new Wall("wall-1", "Keep", UUID.randomUUID(), WORLD,
                ColumnPolygon.rectangle(0, 0, 40, 40), 70, height, Material.STONE_BRICKS, thickness,
                CornerStyle.SHARP);
        wall.profile(profile);
        return wall;
    }

    /** A world whose surface height depends on x, so a wall running along z crosses it. */
    private static FakeGround terrain(IntUnaryOperator topAtX) {
        FakeGround ground = new FakeGround().fillWith("AIR");
        for (int x = -8; x <= 48; x++) {
            for (int z = -8; z <= 48; z++) {
                for (int y = -64; y <= topAtX.applyAsInt(x); y++) {
                    ground.put(new Spot(WORLD, x, y, z), "STONE");
                }
            }
        }
        return ground;
    }

    private static Map<Spot, String> resultOf(List<BatchBuilder.Placement> placements) {
        return placements.stream().collect(Collectors.toMap(BatchBuilder.Placement::spot,
                BatchBuilder.Placement::material, (first, second) -> second));
    }

    /** The lowest block of wall standing in this column, or empty where the wall does not reach. */
    private static java.util.OptionalInt lowestSolidAt(Map<Spot, String> result, int x, int z) {
        return result.entrySet().stream()
                .filter(entry -> entry.getKey().x() == x && entry.getKey().z() == z)
                .filter(entry -> !entry.getValue().equals("AIR"))
                .mapToInt(entry -> entry.getKey().y())
                .min();
    }

    private static java.util.OptionalInt highestSolidAt(Map<Spot, String> result, int x, int z) {
        return result.entrySet().stream()
                .filter(entry -> entry.getKey().x() == x && entry.getKey().z() == z)
                .filter(entry -> !entry.getValue().equals("AIR"))
                .mapToInt(entry -> entry.getKey().y())
                .max();
    }

    @Test
    @DisplayName("the wall follows the ground instead of cutting a level line through it")
    void followsTheTerrain() {
        // A ridge across the middle of the east side: the wall has to climb it.
        Wall keep = wall(WallProfile.town(), 6, 3);
        FakeGround ground = terrain(x -> x >= 18 && x <= 24 ? 84 : 70);

        Map<Spot, String> result = resultOf(builder.buildPlacements(keep, Set.of(), ground));

        int onTheFlat = highestSolidAt(result, 10, 0).orElseThrow();
        int onTheRidge = highestSolidAt(result, 21, 0).orElseThrow();

        assertThat(onTheRidge)
                .as("the wall stayed at one height and cut through the ridge")
                .isGreaterThan(onTheFlat);
    }

    @Test
    @DisplayName("and climbs no faster than a wall can be built, so it steps rather than jumps")
    void climbsInSteps() {
        Wall keep = wall(WallProfile.town(), 6, 3);
        FakeGround ground = terrain(x -> x < 20 ? 70 : 90);

        Map<Spot, String> result = resultOf(builder.buildPlacements(keep, Set.of(), ground));

        int previous = Integer.MIN_VALUE;
        for (int x = 4; x <= 36; x++) {
            var top = highestSolidAt(result, x, 0);
            if (top.isEmpty()) {
                continue;
            }
            if (previous != Integer.MIN_VALUE) {
                assertThat(Math.abs(top.getAsInt() - previous))
                        .as("the wall jumps %d blocks at x=%d", Math.abs(top.getAsInt() - previous), x)
                        .isLessThanOrEqualTo(WallBuildService.MAX_STEP);
            }
            previous = top.getAsInt();
        }
    }

    @Test
    @DisplayName("its footing reaches the ground under it rather than floating over a dip")
    void footsIntoADip() {
        Wall keep = wall(WallProfile.town(), 6, 3);
        FakeGround ground = terrain(x -> x >= 18 && x <= 22 ? 58 : 70);

        Map<Spot, String> result = resultOf(builder.buildPlacements(keep, Set.of(), ground));

        assertThat(lowestSolidAt(result, 20, 0).orElseThrow())
                .as("nothing carries the wall down into the dip")
                .isLessThanOrEqualTo(60);
    }

    @Test
    @DisplayName("there is a walkway on top, and it is clear enough to walk along")
    void hasAWalkwayYouCanWalk() {
        Wall keep = wall(WallProfile.town(), 6, 3);
        FakeGround ground = terrain(x -> 70);

        Map<Spot, String> result = resultOf(builder.buildPlacements(keep, Set.of(), ground));
        int walkY = builder.walkwayHeightAt(keep, ground, new ColumnPolygon.Column(20, 0));

        // The middle of the wall's thickness is floor, and head height above it is air.
        assertThat(result.get(new Spot(WORLD, 20, walkY, 0))).isNotNull().isNotEqualTo("AIR");
        assertThat(result.get(new Spot(WORLD, 20, walkY + 1, 0))).isEqualTo("AIR");
        assertThat(result.get(new Spot(WORLD, 20, walkY + 2, 0))).isEqualTo("AIR");
    }

    @Test
    @DisplayName("the battlements stand on the outer edge, not in the middle of the walk")
    void crenellatesTheOuterEdgeOnly() {
        Wall keep = wall(WallProfile.town(), 6, 3);
        FakeGround ground = terrain(x -> 70);

        Map<Spot, String> result = resultOf(builder.buildPlacements(keep, Set.of(), ground));
        int walkY = builder.walkwayHeightAt(keep, ground, new ColumnPolygon.Column(20, 0));

        // z = -1 is outside the rectangle 0..40, so that is the outer face of the south wall.
        boolean anyMerlonOutside = result.entrySet().stream().anyMatch(entry ->
                entry.getKey().z() == -1 && entry.getKey().y() == walkY + 2
                        && !entry.getValue().equals("AIR"));
        assertThat(anyMerlonOutside).as("nothing stands on the outer edge above the walk").isTrue();
    }

    @Test
    @DisplayName("a ladder goes up to the walkway, on the inside")
    void hasAWayUp() {
        Wall keep = wall(WallProfile.fortress(), 8, 3);
        FakeGround ground = terrain(x -> 70);

        Map<Spot, String> result = resultOf(builder.buildPlacements(keep, Set.of(), ground));

        List<Spot> ladders = result.entrySet().stream()
                .filter(entry -> entry.getValue().equals("LADDER"))
                .map(Map.Entry::getKey).toList();

        assertThat(ladders).as("no way onto the walkway at all").isNotEmpty();
        // Against the wall or inside one of its towers, never out in the field. A corner tower stands
        // proud of the outline by its own width, so its ladder legitimately sits outside the rectangle.
        int towerReach = WallProfile.fortress().towerWidth();
        assertThat(ladders).allSatisfy(spot -> {
            assertThat(spot.x()).isBetween(-towerReach, 40 + towerReach);
            assertThat(spot.z()).isBetween(-towerReach, 40 + towerReach);
        });
        // And it is a climb, not one rung.
        long tallest = ladders.stream().collect(Collectors.groupingBy(
                spot -> spot.x() + "/" + spot.z(), Collectors.counting()))
                .values().stream().mapToLong(Long::longValue).max().orElse(0);
        assertThat(tallest).isGreaterThanOrEqualTo(6L);
    }

    @Test
    @DisplayName("buttresses stand proud of the outer face, spaced along it")
    void buttressesTheOuterFace() {
        Wall keep = wall(WallProfile.fortress(), 8, 3);
        FakeGround ground = terrain(x -> 70);

        Map<Spot, String> result = resultOf(builder.buildPlacements(keep, Set.of(), ground));

        // Two out from the wall's centre line and well above the plinth: only a buttress reaches there.
        long buttressColumns = result.keySet().stream()
                .filter(spot -> spot.z() == -2 && spot.y() == 76 && spot.x() > 0 && spot.x() < 40)
                .map(Spot::x).distinct().count();

        assertThat(buttressColumns).as("the outer face is flat").isPositive();
        assertThat(buttressColumns).as("every column is a buttress, which is a thicker wall")
                .isLessThan(20);
    }

    @Test
    @DisplayName("a plinth runs along the foot of the outer face, wider than the wall above it")
    void standsOnAPlinth() {
        Wall keep = wall(WallProfile.town(), 8, 3);
        FakeGround ground = terrain(x -> 70);

        Map<Spot, String> result = resultOf(builder.buildPlacements(keep, Set.of(), ground));

        // One block outside the south face, at the very bottom: that is plinth and nothing else.
        long plinthColumns = result.keySet().stream()
                .filter(spot -> spot.z() == -1 && spot.y() == 71)
                .map(Spot::x).distinct().count();

        assertThat(plinthColumns).as("the wall meets the ground with no base course at all")
                .isGreaterThan(20);
    }

    @Test
    @DisplayName("the top course corbels outward, the way a wall-walk is carried in every reference")
    void corbelsUnderTheWalk() {
        Wall keep = wall(WallProfile.town(), 8, 3);
        FakeGround ground = terrain(x -> 70);

        Map<Spot, String> result = resultOf(builder.buildPlacements(keep, Set.of(), ground));
        int walkY = builder.walkwayHeightAt(keep, ground, new ColumnPolygon.Column(20, 0));

        // Directly under the walk, one block proud of the face.
        assertThat(result.get(new Spot(WORLD, 20, walkY - 1, -1)))
                .as("the face runs straight up to the battlements with no cornice")
                .isNotNull();
    }

    @Test
    @DisplayName("lanterns sit in that cornice, spaced along it")
    void lightsTheCornice() {
        Wall keep = wall(WallProfile.town(), 8, 3);
        FakeGround ground = terrain(x -> 70);

        Map<Spot, String> result = resultOf(builder.buildPlacements(keep, Set.of(), ground));

        long lanterns = result.values().stream().filter(name -> name.contains("LANTERN")).count();

        assertThat(lanterns).as("an unlit wall at night is a black stripe").isPositive();
        assertThat(lanterns).as("a lantern every block is a runway, not a castle").isLessThan(40);
    }

    @Test
    @DisplayName("every preset builds a solid wall — no holes in the face somebody has to fill in later")
    void presetsBuildSolidWalls() {
        FakeGround ground = terrain(x -> 70);

        for (WallProfile profile : List.of(WallProfile.simple(), WallProfile.town(), WallProfile.fortress())) {
            Wall keep = wall(profile, 10, 3);
            Map<Spot, String> result = resultOf(builder.buildPlacements(keep, Set.of(), ground));

            for (int x = 6; x <= 34; x++) {
                assertThat(result.get(new Spot(WORLD, x, 76, -1)))
                        .as("a hole in the face of a wall built from the %s preset, at x=%d", profile, x)
                        .isNotEqualTo("AIR");
            }
        }
    }

    @Test
    @DisplayName("recessed panels are still there for anybody who asks for them")
    void recessesTheFaceWhenAsked() {
        WallProfile arched = new WallProfile(true, 2, true, org.bukkit.Material.STONE_BRICKS, true,
                org.bukkit.Material.STONE_BRICKS, 6, 16, null, 24, 4, 2,
                true, true, org.bukkit.Material.LANTERN, 6, true);
        Wall keep = wall(arched, 10, 3);
        FakeGround ground = terrain(x -> 70);

        Map<Spot, String> result = resultOf(builder.buildPlacements(keep, Set.of(), ground));

        List<String> alongTheFace = new java.util.ArrayList<>();
        for (int x = 4; x <= 36; x++) {
            alongTheFace.add(String.valueOf(result.get(new Spot(WORLD, x, 76, -1))));
        }
        assertThat(alongTheFace).as("the face is one unbroken slab of stone").contains("AIR");
        assertThat(alongTheFace).as("the whole face was hollowed out").contains("STONE_BRICKS");
    }

    @Test
    @DisplayName("a recess never goes all the way through — a wall you can see daylight through is a fence")
    void neverRecessesThroughTheWall() {
        Wall keep = wall(WallProfile.fortress(), 10, 3);
        FakeGround ground = terrain(x -> 70);

        Map<Spot, String> result = resultOf(builder.buildPlacements(keep, Set.of(), ground));

        for (int x = 4; x <= 36; x++) {
            for (int y = 72; y <= 78; y++) {
                boolean allAir = true;
                for (int z = -1; z <= 1; z++) {
                    String at = result.get(new Spot(WORLD, x, y, z));
                    if (at != null && !at.equals("AIR")) {
                        allAir = false;
                        break;
                    }
                }
                assertThat(allAir)
                        .as("you can see straight through the wall at x=%d, y=%d", x, y)
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("a tower has a floor you can stand on at the top, not just walls around a hole")
    void towersHaveARoofToStandOn() {
        Wall keep = wall(WallProfile.fortress(), 8, 3);
        FakeGround ground = terrain(x -> 70);

        Map<Spot, String> result = resultOf(builder.buildPlacements(keep, Set.of(), ground));

        // The corner tower at 0/0: its middle at the top has to be floor, with air over it.
        int roofY = 71 + 8 + WallProfile.fortress().towerRise();
        assertThat(result.get(new Spot(WORLD, 0, roofY, 0)))
                .as("the top of the tower is open air — there is nothing to stand on")
                .isNotNull().isNotEqualTo("AIR");
        assertThat(result.get(new Spot(WORLD, 0, roofY + 1, 0))).isEqualTo("AIR");
    }

    @Test
    @DisplayName("and battlements around that platform, so standing on it is standing behind something")
    void towerPlatformsAreCrenellated() {
        Wall keep = wall(WallProfile.fortress(), 8, 3);
        FakeGround ground = terrain(x -> 70);

        Map<Spot, String> result = resultOf(builder.buildPlacements(keep, Set.of(), ground));
        int roofY = 71 + 8 + WallProfile.fortress().towerRise();

        long merlons = result.entrySet().stream()
                .filter(entry -> entry.getKey().y() == roofY + 1)
                .filter(entry -> !entry.getValue().equals("AIR"))
                .count();
        assertThat(merlons).as("the platform has no parapet at all").isPositive();
    }

    @Test
    @DisplayName("a tower is enterable from the wall-walk, and climbable from there to its top")
    void towersAreEnterableAndClimbable() {
        Wall keep = wall(WallProfile.fortress(), 8, 3);
        FakeGround ground = terrain(x -> 70);

        Map<Spot, String> result = resultOf(builder.buildPlacements(keep, Set.of(), ground));
        int walkY = builder.walkwayHeightAt(keep, ground, new ColumnPolygon.Column(0, 0));

        // A floor level with the wall's own walk, so you step into the tower rather than off it.
        assertThat(result.get(new Spot(WORLD, 0, walkY, 0))).isNotNull().isNotEqualTo("AIR");
        // And a ladder inside it going up.
        long laddersInsideTheTower = result.entrySet().stream()
                .filter(entry -> entry.getValue().equals("LADDER"))
                .filter(entry -> Math.abs(entry.getKey().x()) <= 2 && Math.abs(entry.getKey().z()) <= 2)
                .count();
        assertThat(laddersInsideTheTower).as("no way from the walk up onto the tower").isPositive();
    }

    @Test
    @DisplayName("a plain wall is still a plain wall — no walk, no ladders, no buttresses")
    void plainWallStaysPlain() {
        Wall plain = wall(WallProfile.simple(), 6, 1);
        FakeGround ground = terrain(x -> 70);

        Map<Spot, String> result = resultOf(builder.buildPlacements(plain, Set.of(), ground));

        assertThat(result.values()).doesNotContain("LADDER");
        assertThat(result.keySet()).noneMatch(spot -> spot.z() == -2);
        assertThat(result.values()).noneMatch(name -> name.contains("LANTERN"));
    }

    @Test
    @DisplayName("a gate opening is still left out of all of it")
    void leavesGatesOpen() {
        Wall keep = wall(WallProfile.town(), 6, 3);
        FakeGround ground = terrain(x -> 70);
        Set<ColumnPolygon.Column> opening = Set.of(new ColumnPolygon.Column(20, 0));

        Map<Spot, String> result = resultOf(builder.buildPlacements(keep, opening, ground));

        // Cleared, not skipped: the wall is built through and the opening is cut out of it, so a
        // rebuild over a standing wall takes the old blocks away instead of leaving them behind.
        int walkY = builder.walkwayHeightAt(keep, ground, new ColumnPolygon.Column(20, 0));
        for (int y = 70; y < walkY; y++) {
            assertThat(result.get(new Spot(WORLD, 20, y, 0)))
                    .as("the gate at y=%d was bricked up by the wall itself", y)
                    .isIn(null, "AIR");
        }
    }
}
