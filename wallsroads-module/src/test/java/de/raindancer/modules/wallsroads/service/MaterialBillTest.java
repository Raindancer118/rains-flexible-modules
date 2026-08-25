package de.raindancer.modules.wallsroads.service;

import de.raindancer.core.world.build.BatchBuilder;
import de.raindancer.core.world.safety.Spot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What a build costs, counted before anybody is charged for it. */
class MaterialBillTest {

    private final MaterialBill bill = new MaterialBill();

    private static BatchBuilder.Placement place(int x, String material) {
        return new BatchBuilder.Placement(new Spot("world", x, 70, 0), material);
    }

    @Test
    @DisplayName("the bill is one line per material, counted")
    void countsByMaterial() {
        Map<String, Integer> cost = bill.costOf(List.of(
                place(0, "STONE_BRICKS"), place(1, "STONE_BRICKS"), place(2, "OAK_FENCE")));

        assertThat(cost).containsEntry("STONE_BRICKS", 2).containsEntry("OAK_FENCE", 1);
    }

    @Test
    @DisplayName("clearing a block costs nothing — air is not a material anybody pays for")
    void airIsFree() {
        Map<String, Integer> cost = bill.costOf(List.of(place(0, "AIR"), place(1, "STONE")));

        assertThat(cost).containsOnlyKeys("STONE");
    }

    @Test
    @DisplayName("what is missing is the bill minus what is carried, and nothing else")
    void reportsOnlyTheShortfall() {
        Map<String, Integer> cost = Map.of("STONE_BRICKS", 100, "OAK_FENCE", 10);

        Map<String, Integer> missing = bill.shortfall(cost, Map.of("STONE_BRICKS", 60, "OAK_FENCE", 40));

        assertThat(missing).containsExactly(Map.entry("STONE_BRICKS", 40));
    }

    @Test
    @DisplayName("enough of everything is no shortfall at all")
    void nothingMissingWhenItIsAllThere() {
        assertThat(bill.shortfall(Map.of("STONE", 5), Map.of("STONE", 5))).isEmpty();
    }

    @Test
    @DisplayName("a build stops where the materials ran out, rather than being refused whole")
    void truncatesToWhatCanBePaidFor() {
        List<BatchBuilder.Placement> wanted = List.of(
                place(0, "STONE"), place(1, "STONE"), place(2, "STONE"), place(3, "STONE"));

        List<BatchBuilder.Placement> affordable = bill.affordable(wanted, Map.of("STONE", 2));

        assertThat(affordable).hasSize(2);
    }

    @Test
    @DisplayName("clearing is always affordable, even mid-build, or a tunnel stops half-bored")
    void clearingIsAlwaysAffordable() {
        List<BatchBuilder.Placement> wanted = List.of(
                place(0, "AIR"), place(1, "STONE"), place(2, "AIR"), place(3, "STONE"));

        List<BatchBuilder.Placement> affordable = bill.affordable(wanted, Map.of("STONE", 1));

        assertThat(affordable).extracting(BatchBuilder.Placement::material)
                .containsExactly("AIR", "STONE", "AIR");
    }
}
