package de.raindancer.modules.wallsroads.service;

import de.raindancer.core.world.build.BatchBuilder;
import de.raindancer.core.world.geometry.ColumnPolygon;
import de.raindancer.core.world.geometry.ColumnPolygon.Column;
import de.raindancer.core.world.safety.Spot;
import de.raindancer.modules.wallsroads.model.CornerStyle;
import de.raindancer.modules.wallsroads.model.Gate;
import de.raindancer.modules.wallsroads.model.Wall;
import de.raindancer.modules.wallsroads.model.WallProfile;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A gate that reads as a gate: a round arch, a frame around it, and doors that fill the arch when it
 * is shut.
 *
 * <p>What this replaces was a rectangular hole punched through the wall — literally
 * {@code cutPlacements} setting the opening columns to air — which on the test server came out as a
 * ragged gap with a stepped edge and nothing in it.
 */
class GatehouseTest {

    private static final String WORLD = "world";

    private final GateService gates = new GateService();

    private static Wall wall() {
        Wall wall = new Wall("wall-1", "Keep", UUID.randomUUID(), WORLD,
                ColumnPolygon.rectangle(0, 0, 40, 40), 70, 8, Material.STONE_BRICKS, 3,
                CornerStyle.SHARP);
        wall.profile(WallProfile.town());
        return wall;
    }

    /** An opening seven columns wide across the south face. */
    private static Gate gate(int width) {
        List<Column> columns = new ArrayList<>();
        for (int x = 20 - width / 2; x <= 20 + width / 2; x++) {
            columns.add(new Column(x, 0));
        }
        return new Gate("gate-1", "wall-1", "road-1", columns, 6, false);
    }

    private static Map<Spot, String> resultOf(List<BatchBuilder.Placement> placements) {
        return placements.stream().collect(Collectors.toMap(BatchBuilder.Placement::spot,
                BatchBuilder.Placement::material, (first, second) -> second));
    }

    @Test
    @DisplayName("the opening is an arch — tallest in the middle, curving down to the sides")
    void cutsARoundArch() {
        Wall keep = wall();
        Gate gate = gate(7);

        Map<Spot, String> opened = resultOf(gates.openPlacements(keep, gate));

        int middle = highestAirAt(opened, 20);
        int nextToIt = highestAirAt(opened, 22);
        int atTheEdge = highestAirAt(opened, 23);

        assertThat(middle).as("the middle of the arch is no taller than its side").isGreaterThan(atTheEdge);
        assertThat(nextToIt).as("the arch does not curve, it steps once")
                .isBetween(atTheEdge, middle);
    }

    @Test
    @DisplayName("and it is a passage all the way through the wall, not a niche in its face")
    void goesAllTheWayThrough() {
        Wall keep = wall();
        Gate gate = gate(7);

        Map<Spot, String> opened = resultOf(gates.openPlacements(keep, gate));

        // The wall is three thick: the opening has to clear all three courses at the middle.
        for (int z = -1; z <= 1; z++) {
            assertThat(opened.get(new Spot(WORLD, 20, 71, z)))
                    .as("the passage is blocked at z=%d", z)
                    .isEqualTo("AIR");
        }
    }

    @Test
    @DisplayName("shutting it fills exactly the arch, in wood, and nothing outside it")
    void shutsWithDoorsThatFitTheArch() {
        Wall keep = wall();
        Gate gate = gate(7).withDoor(Material.OAK_PLANKS);

        Map<Spot, String> opened = resultOf(gates.openPlacements(keep, gate));
        Map<Spot, String> shut = resultOf(gates.shutPlacements(keep, gate));

        // Every block the opening cleared is filled again...
        for (Map.Entry<Spot, String> entry : opened.entrySet()) {
            assertThat(shut.get(entry.getKey()))
                    .as("the shut gate leaves %s open", entry.getKey())
                    .isEqualTo("OAK_PLANKS");
        }
        // ...and nothing else is touched.
        assertThat(shut.keySet()).isEqualTo(opened.keySet());
    }

    @Test
    @DisplayName("a one-wide gate is still a gate rather than a divide-by-zero")
    void survivesTheNarrowestGate() {
        assertThat(gates.openPlacements(wall(), gate(1))).isNotEmpty();
    }

    @Test
    @DisplayName("an opening taller than the wall is cut to the wall, not through the sky above it")
    void neverCutsPastTheWall() {
        Wall keep = wall();
        Gate tall = new Gate("gate-2", "wall-1", "road-1", gate(7).openingColumns(), 40, false);

        Map<Spot, String> opened = resultOf(gates.openPlacements(keep, tall));

        assertThat(opened.keySet()).allSatisfy(spot ->
                assertThat(spot.y()).isLessThan(keep.minY() + keep.height() + 1));
    }

    /** The highest block the opening clears in this column. */
    private static int highestAirAt(Map<Spot, String> opened, int x) {
        return opened.keySet().stream().filter(spot -> spot.x() == x && spot.z() == 0)
                .mapToInt(Spot::y).max().orElse(Integer.MIN_VALUE);
    }
}
