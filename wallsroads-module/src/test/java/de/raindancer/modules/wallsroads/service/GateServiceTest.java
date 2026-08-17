package de.raindancer.modules.wallsroads.service;

import de.raindancer.core.world.geometry.ColumnPolygon;
import de.raindancer.core.world.geometry.Polyline;
import de.raindancer.modules.wallsroads.model.CornerStyle;
import de.raindancer.modules.wallsroads.model.ElevationMode;
import de.raindancer.modules.wallsroads.model.Gate;
import de.raindancer.modules.wallsroads.model.RoadPath;
import de.raindancer.modules.wallsroads.model.Wall;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GateServiceTest {

    private final GateService service = new GateService();

    @Test
    void roadCrossingAWallCutsExactlyOneGate() {
        Wall wall = new Wall("wall-1", "Town Wall", UUID.randomUUID(), "world",
                ColumnPolygon.rectangle(0, 0, 40, 40), 64, 6, Material.STONE_BRICKS, 1, CornerStyle.SHARP);
        // A road running straight through the west wall face (x = 0) at z = 20.
        RoadPath road = new RoadPath("road-1", "Main Road", UUID.randomUUID(), "world",
                new Polyline(List.of(new ColumnPolygon.Column(-10, 20), new ColumnPolygon.Column(10, 20))),
                4, Material.GRAVEL, ElevationMode.FIXED_Y, 64);

        List<Gate> gates = service.detect(wall, road, 4);

        assertThat(gates).hasSize(1);
        Gate gate = gates.get(0);
        assertThat(gate.wallId()).isEqualTo("wall-1");
        assertThat(gate.roadId()).isEqualTo("road-1");
        assertThat(gate.height()).isEqualTo(4);
        assertThat(gate.openingColumns()).allMatch(column -> column.x() == 0);
        assertThat(gate.sealed()).isFalse();
    }

    @Test
    void roadNotTouchingTheWallCutsNoGate() {
        Wall wall = new Wall("wall-1", "Town Wall", UUID.randomUUID(), "world",
                ColumnPolygon.rectangle(0, 0, 40, 40), 64, 6, Material.STONE_BRICKS, 1, CornerStyle.SHARP);
        RoadPath farAway = new RoadPath("road-2", "Far Road", UUID.randomUUID(), "world",
                new Polyline(List.of(new ColumnPolygon.Column(200, 200), new ColumnPolygon.Column(210, 200))),
                4, Material.GRAVEL, ElevationMode.FIXED_Y, 64);

        assertThat(service.detect(wall, farAway, 4)).isEmpty();
    }

    @Test
    void twoRoadsCrossingTwoDifferentFacesProduceTwoSeparateGates() {
        Wall wall = new Wall("wall-1", "Town Wall", UUID.randomUUID(), "world",
                ColumnPolygon.rectangle(0, 0, 40, 40), 64, 6, Material.STONE_BRICKS, 1, CornerStyle.SHARP);
        RoadPath west = new RoadPath("road-west", "West Road", UUID.randomUUID(), "world",
                new Polyline(List.of(new ColumnPolygon.Column(-10, 20), new ColumnPolygon.Column(10, 20))),
                4, Material.GRAVEL, ElevationMode.FIXED_Y, 64);
        RoadPath north = new RoadPath("road-north", "North Road", UUID.randomUUID(), "world",
                new Polyline(List.of(new ColumnPolygon.Column(20, -10), new ColumnPolygon.Column(20, 10))),
                4, Material.GRAVEL, ElevationMode.FIXED_Y, 64);

        List<Gate> westGates = service.detect(wall, west, 4);
        List<Gate> northGates = service.detect(wall, north, 4);

        assertThat(westGates).hasSize(1);
        assertThat(northGates).hasSize(1);
        assertThat(westGates.get(0).id()).isNotEqualTo(northGates.get(0).id());
    }

    @Test
    void sealingAGateFlagsItWithoutLosingItsColumns() {
        Gate gate = new Gate("gate-1", "wall-1", "road-1",
                List.of(new ColumnPolygon.Column(0, 20)), 4, false);
        Gate sealed = gate.asSealed(true);

        assertThat(sealed.sealed()).isTrue();
        assertThat(sealed.openingColumns()).isEqualTo(gate.openingColumns());
        assertThat(gate.sealed()).isFalse();
    }
}
