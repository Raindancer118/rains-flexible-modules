package de.raindancer.modules.arena.rules;

import de.raindancer.modules.arena.model.Box;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BasinRuleTest {

    private final BasinRule rule = new BasinRule();

    /** A 10×10 floor at y 0, walls round x/z 0 and 9 up to y 5 — a closed basin. */
    private static BasinRule.Probe walled(Set<String> holes) {
        return (x, y, z) -> {
            if (holes.contains(x + "," + y + "," + z)) {
                return true;
            }
            if (y <= 0) {
                return false;
            }
            boolean wall = x == 0 || x == 9 || z == 0 || z == 9;
            return !(wall && y <= 5);
        };
    }

    private static final Box BOX = Box.of("arena", 0, 0, 0, 9, 10, 9);

    @Test
    @DisplayName("a closed basin fills its inside, layer by layer, and needs no seal at all")
    void closed() {
        BasinRule.Basin basin = rule.plan(BOX, 1, 3, List.of(new int[]{5, 5}), walled(Set.of()), 100_000);
        assertThat(basin.layers()).hasSize(3);
        assertThat(basin.layers().getFirst().water()).hasSize(64);
        assertThat(basin.layers().stream().allMatch(layer -> layer.seals().isEmpty())).isTrue();
        assertThat(basin.layers().get(2).y()).isEqualTo(3);
    }

    @Test
    @DisplayName("a gate in the wall is sealed, never flooded through — the water stays in the pit")
    void gate() {
        BasinRule.Basin basin = rule.plan(BOX, 1, 1, List.of(new int[]{5, 5}),
                walled(Set.of("0,1,4")), 100_000);
        BasinRule.Layer layer = basin.layers().getFirst();
        assertThat(layer.water()).contains(new BasinRule.Cell(0, 1, 4)).as("the gate is inside the box");
        assertThat(layer.seals()).contains(new BasinRule.Cell(-1, 1, 4)).as("the outside of the gate is sealed");
    }

    @Test
    @DisplayName("a hole in the floor is sealed underneath, so the water does not drain into it")
    void holeBelow() {
        BasinRule.Basin basin = rule.plan(BOX, 1, 1, List.of(new int[]{5, 5}), walled(Set.of("3,0,3")), 100_000);
        assertThat(basin.layers().getFirst().seals()).containsExactly(new BasinRule.Cell(3, 0, 3));
    }

    @Test
    @DisplayName("a pillar stays dry; every water cell is reachable from where the fight starts")
    void pillar() {
        Set<String> solid = new HashSet<>(List.of("4,1,4", "4,2,4"));
        BasinRule.Probe probe = (x, y, z) -> !solid.contains(x + "," + y + "," + z) && walled(Set.of()).open(x, y, z);
        BasinRule.Basin basin = rule.plan(BOX, 1, 2, List.of(new int[]{2, 2}), probe, 100_000);
        assertThat(basin.layers().getFirst().water()).doesNotContain(new BasinRule.Cell(4, 1, 4)).hasSize(63);
    }

    @Test
    @DisplayName("a basin larger than allowed is not flooded at all")
    void tooBig() {
        BasinRule.Basin basin = rule.plan(BOX, 1, 3, List.of(new int[]{5, 5}), walled(Set.of()), 100);
        assertThat(basin.layers()).isEmpty();
        assertThat(basin.tooBig()).isTrue();
    }
}
