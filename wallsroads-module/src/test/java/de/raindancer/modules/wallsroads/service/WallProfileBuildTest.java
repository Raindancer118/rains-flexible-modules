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
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** A wall that looks built rather than extruded: footings, a walkway, battlements, towers. */
class WallProfileBuildTest {

    private static final String WORLD = "world";

    private final WallBuildService builder = new WallBuildService();

    private static Wall wall(WallProfile profile, int height) {
        Wall wall = new Wall("wall-1", "Town Wall", UUID.randomUUID(), WORLD,
                ColumnPolygon.rectangle(0, 0, 20, 20), 70, height, Material.STONE_BRICKS, 2,
                CornerStyle.SHARP);
        wall.profile(profile);
        return wall;
    }

    private static FakeGround land(int groundTop) {
        FakeGround ground = new FakeGround().fillWith("AIR");
        for (int x = -4; x <= 24; x++) {
            for (int z = -4; z <= 24; z++) {
                for (int y = -64; y <= groundTop; y++) {
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

    @Test
    @DisplayName("a plain wall is the same solid block of stone it always was")
    void plainWallIsUnchanged() {
        Wall plain = wall(WallProfile.simple(), 6);

        Map<Spot, String> result = resultOf(builder.buildPlacements(plain, Set.of(), land(69)));

        assertThat(result.get(new Spot(WORLD, 10, 70, 0))).isEqualTo("STONE_BRICKS");
        assertThat(result.get(new Spot(WORLD, 10, 75, 0))).isEqualTo("STONE_BRICKS");
        assertThat(result.get(new Spot(WORLD, 10, 76, 0))).isNull();
    }

    @Test
    @DisplayName("battlements leave every other column of the top row open")
    void crenellatesTheTop() {
        Wall town = wall(WallProfile.town(), 6);

        Map<Spot, String> result = resultOf(builder.buildPlacements(town, Set.of(), land(69)));

        List<String> topRow = List.of(
                String.valueOf(result.get(new Spot(WORLD, 8, 75, 0))),
                String.valueOf(result.get(new Spot(WORLD, 9, 75, 0))),
                String.valueOf(result.get(new Spot(WORLD, 10, 75, 0))),
                String.valueOf(result.get(new Spot(WORLD, 11, 75, 0))));
        assertThat(topRow).contains("STONE_BRICKS");
        assertThat(topRow).contains("AIR");
    }

    @Test
    @DisplayName("a wall on a slope carries its footing down to the ground rather than floating")
    void footsTheWallToTheGround() {
        Wall town = wall(WallProfile.town(), 6);
        FakeGround uneven = land(69);
        for (int z = -4; z <= 24; z++) {
            for (int y = 60; y <= 69; y++) {
                uneven.put(new Spot(WORLD, 10, y, z), "AIR");
            }
        }

        Map<Spot, String> result = resultOf(builder.buildPlacements(town, Set.of(), uneven));

        assertThat(result.get(new Spot(WORLD, 10, 65, 0))).isEqualTo("STONE_BRICKS");
        assertThat(result.get(new Spot(WORLD, 10, 60, 0))).isEqualTo("STONE_BRICKS");
    }

    @Test
    @DisplayName("towers stand at the corners, wider and taller than the wall they anchor")
    void raisesCornerTowers() {
        Wall fortress = wall(WallProfile.fortress(), 6);

        Map<Spot, String> result = resultOf(builder.buildPlacements(fortress, Set.of(), land(69)));

        int wallTop = 75;
        // Above the wall's own top, so this is tower and nothing else.
        assertThat(result.get(new Spot(WORLD, 2, wallTop + 2, 0))).isEqualTo("STONE_BRICKS");
        // Wider than the wall: a corner block two out on both axes is tower.
        assertThat(result.get(new Spot(WORLD, 2, wallTop + 2, 2))).isEqualTo("STONE_BRICKS");
        // And hollow, so somebody can stand in it.
        assertThat(result.get(new Spot(WORLD, 0, wallTop + 2, 0))).isNull();
    }

    @Test
    @DisplayName("a gate opening is still left out of a profiled wall, battlements and all")
    void leavesGatesOpen() {
        Wall town = wall(WallProfile.town(), 6);
        Set<ColumnPolygon.Column> opening = Set.of(new ColumnPolygon.Column(10, 0));

        Map<Spot, String> result = resultOf(builder.buildPlacements(town, opening, land(69)));

        assertThat(result.get(new Spot(WORLD, 10, 70, 0))).isNull();
        assertThat(result.get(new Spot(WORLD, 10, 75, 0))).isNull();
    }
}
