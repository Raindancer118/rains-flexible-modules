package de.raindancer.modules.arena.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class NavalSpotsRuleTest {

    private final NavalSpotsRule rule = new NavalSpotsRule();

    /** Open water from x -40..40, z -20..20, except a pillar at the centre. */
    private static Set<BasinRule.Cell> water() {
        Set<BasinRule.Cell> cells = new HashSet<>();
        for (int x = -40; x <= 40; x++) {
            for (int z = -20; z <= 20; z++) {
                if (Math.abs(x) > 2 || Math.abs(z) > 2) {
                    cells.add(new BasinRule.Cell(x, 67, z));
                }
            }
        }
        return cells;
    }

    @Test
    @DisplayName("two boats start where the pit's two spawns are, facing each other")
    void two() {
        List<NavalSpotsRule.Spot> spots = rule.spots(2, -38.5, 0.5, 38.5, 0.5, water(), -40, 40, -20, 20);
        assertThat(spots).hasSize(2);
        assertThat(spots.get(0).x()).as("-38.5 is in block -39").isEqualTo(-39);
        assertThat(spots.get(1).x()).isEqualTo(38);
        assertThat(spots.get(0).yaw()).as("looking east, to the middle").isEqualTo(-90f, org.assertj.core.data.Offset.offset(1f));
    }

    @Test
    @DisplayName("eight boats spread round a ring, every one on water and no two on the same cell")
    void eight() {
        Set<BasinRule.Cell> water = water();
        List<NavalSpotsRule.Spot> spots = rule.spots(8, -38.5, 0.5, 38.5, 0.5, water, -40, 40, -20, 20);
        assertThat(spots).hasSize(8);
        assertThat(spots.stream().map(spot -> spot.x() + "," + spot.z()).distinct().count()).isEqualTo(8);
        assertThat(spots).allSatisfy(spot -> assertThat(water).contains(new BasinRule.Cell(spot.x(), 67, spot.z())));
        assertThat(spots).allSatisfy(spot -> assertThat(Math.abs(spot.z())).isLessThanOrEqualTo(20));
    }

    @Test
    @DisplayName("no water, no spots — the fight is not started on dry land")
    void dry() {
        assertThat(rule.spots(2, -38.5, 0.5, 38.5, 0.5, Set.of(), -40, 40, -20, 20)).isEmpty();
    }
}
