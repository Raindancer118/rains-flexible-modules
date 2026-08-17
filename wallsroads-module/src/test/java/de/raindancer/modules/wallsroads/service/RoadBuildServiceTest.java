package de.raindancer.modules.wallsroads.service;

import de.raindancer.core.world.build.BatchBuilder;
import de.raindancer.core.world.geometry.ColumnPolygon;
import de.raindancer.core.world.geometry.Polyline;
import de.raindancer.core.world.safety.Spot;
import de.raindancer.modules.wallsroads.model.ElevationMode;
import de.raindancer.modules.wallsroads.model.RoadPath;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RoadBuildServiceTest {

    private final RoadBuildService service = new RoadBuildService();

    private RoadPath straightRoad(ElevationMode mode) {
        return new RoadPath("road-1", "Main Road", UUID.randomUUID(), "world",
                new Polyline(List.of(new ColumnPolygon.Column(0, 0), new ColumnPolygon.Column(20, 0))),
                5, Material.GRAVEL, mode, 70);
    }

    @Test
    void fixedYPavesEveryColumnAtTheSameHeight() {
        RoadPath road = straightRoad(ElevationMode.FIXED_Y);
        Map<ColumnPolygon.Column, Integer> heights = service.surfaceHeights(road, new FakeGround());

        assertThat(heights).isNotEmpty();
        assertThat(heights.values()).allMatch(y -> y == 70);
    }

    @Test
    void buildThenTeardownRestoresTheOriginalGround() {
        RoadPath road = straightRoad(ElevationMode.FIXED_Y);
        FakeGround ground = new FakeGround().fillWith("GRASS_BLOCK");

        BatchBuilder build = service.newBuild(ground, road);
        build.advance(build.total());
        assertThat(ground.materialAt(new Spot("world", 10, 70, 0))).isEqualTo("GRAVEL");

        road.markBuilt(build.snapshotSoFar());
        BatchBuilder teardown = service.newTeardown(ground, road);
        teardown.advance(teardown.total());

        assertThat(ground.materialAt(new Spot("world", 10, 70, 0))).isEqualTo("GRASS_BLOCK");
    }

    @Test
    void followTerrainTracksAStepInTheGround() {
        RoadPath road = straightRoad(ElevationMode.FOLLOW_TERRAIN);
        FakeGround ground = new FakeGround().fillWith("AIR");
        // Flat ground at y=63 for the first half, a step up to y=70 for the second half.
        for (int x = 0; x <= 10; x++) {
            ground.put(new Spot("world", x, 63, 0), "STONE");
        }
        for (int x = 11; x <= 20; x++) {
            ground.put(new Spot("world", x, 70, 0), "STONE");
        }

        Map<ColumnPolygon.Column, Integer> heights = service.surfaceHeights(road, ground);

        assertThat(heights.get(new ColumnPolygon.Column(0, 0))).isEqualTo(64);
        assertThat(heights.get(new ColumnPolygon.Column(20, 0))).isEqualTo(71);
    }
}
