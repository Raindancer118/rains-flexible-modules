package de.raindancer.modules.wallsroads.service;

import de.raindancer.core.world.build.BatchBuilder;
import de.raindancer.core.world.geometry.ColumnPolygon;
import de.raindancer.core.world.safety.Spot;
import de.raindancer.modules.wallsroads.model.CornerStyle;
import de.raindancer.modules.wallsroads.model.Wall;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WallBuildServiceTest {

    private final WallBuildService service = new WallBuildService();

    /**
     * A world with a surface at y=63, so the ground under a wall is y=64.
     *
     * <p>Not {@code fillWith("GRASS_BLOCK")}: a world that is solid all the way to the build limit has
     * its surface at the build limit, and a wall that follows the ground follows it up there. That is
     * the terrain reader being right about a world that could not exist.
     */
    private static FakeGround landAt(int surfaceY) {
        FakeGround ground = new FakeGround().fillWith("AIR");
        for (int x = -4; x <= 24; x++) {
            for (int z = -4; z <= 24; z++) {
                for (int y = -64; y <= surfaceY; y++) {
                    ground.put(new Spot("world", x, y, z), "GRASS_BLOCK");
                }
            }
        }
        return ground;
    }

    private Wall rectangleWall(int thickness, CornerStyle style) {
        ColumnPolygon outline = ColumnPolygon.rectangle(0, 0, 20, 20);
        return new Wall("wall-1", "Town Wall", UUID.randomUUID(), "world", outline,
                64, 6, Material.STONE_BRICKS, thickness, style);
    }

    @Test
    void footprintFollowsTheOutlineAtTheConfiguredThickness() {
        Wall wall = rectangleWall(2, CornerStyle.SHARP);
        Set<ColumnPolygon.Column> footprint = service.footprint(wall);

        assertThat(footprint).contains(new ColumnPolygon.Column(0, 0));
        assertThat(footprint).contains(new ColumnPolygon.Column(10, 0));
        // The interior, far from every edge, is not part of a thin wall's footprint.
        assertThat(footprint).doesNotContain(new ColumnPolygon.Column(10, 10));
    }

    @Test
    void roundedWallHasNoSharpFootprintCorner() {
        Wall sharp = rectangleWall(1, CornerStyle.SHARP);
        Wall rounded = rectangleWall(1, CornerStyle.rounded(5));

        assertThat(service.footprint(rounded)).isNotEqualTo(service.footprint(sharp));
        assertThat(service.footprint(rounded)).doesNotContain(new ColumnPolygon.Column(0, 0));
    }

    @Test
    void buildThenTeardownRestoresWhatWasThereBefore() {
        Wall wall = rectangleWall(1, CornerStyle.SHARP);
        FakeGround ground = landAt(63);

        BatchBuilder build = service.newBuild(ground, wall, Set.of());
        build.advance(build.total());
        assertThat(ground.materialAt(new Spot("world", 0, 64, 0))).isEqualTo("STONE_BRICKS");

        wall.markBuilt(build.snapshotSoFar());
        BatchBuilder teardown = service.newTeardown(ground, wall);
        teardown.advance(teardown.total());

        assertThat(ground.materialAt(new Spot("world", 0, 64, 0))).isEqualTo("AIR");
    }

    @Test
    void gateOpeningsAreExcludedFromTheBuild() {
        Wall wall = rectangleWall(1, CornerStyle.SHARP);
        FakeGround ground = landAt(63);
        Set<ColumnPolygon.Column> opening = Set.of(new ColumnPolygon.Column(0, 0));

        BatchBuilder build = service.newBuild(ground, wall, opening);
        build.advance(build.total());

        assertThat(ground.materialAt(new Spot("world", 0, 64, 0))).isEqualTo("AIR");
        assertThat(ground.materialAt(new Spot("world", 1, 64, 0))).isEqualTo("STONE_BRICKS");
    }

    @Test
    void sealingRebuildsExactlyTheOpeningColumns() {
        Wall wall = rectangleWall(1, CornerStyle.SHARP);
        FakeGround ground = landAt(63);
        Set<ColumnPolygon.Column> opening = Set.of(new ColumnPolygon.Column(0, 0));

        BatchBuilder build = service.newBuild(ground, wall, opening);
        build.advance(build.total());
        assertThat(ground.materialAt(new Spot("world", 0, 64, 0))).isEqualTo("AIR");

        BatchBuilder seal = new BatchBuilder(ground, service.sealPlacements(wall, opening));
        seal.advance(seal.total());
        assertThat(ground.materialAt(new Spot("world", 0, 64, 0))).isEqualTo("STONE_BRICKS");
    }
}
