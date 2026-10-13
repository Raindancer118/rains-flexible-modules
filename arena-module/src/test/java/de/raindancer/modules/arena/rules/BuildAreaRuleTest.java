package de.raindancer.modules.arena.rules;

import de.raindancer.modules.arena.model.Box;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BuildAreaRuleTest {

    private final BuildAreaRule rule = new BuildAreaRule();

    @Test
    @DisplayName("the area is the floor with a margin round it, a little below and well above")
    void aroundTheFloor() {
        Box floor = Box.of("arena", 0, 64, 0, 19, 66, 19);

        Box area = rule.around(floor, -64, 319);

        assertThat(area.minX()).isEqualTo(-BuildAreaRule.MARGIN);
        assertThat(area.maxX()).isEqualTo(19 + BuildAreaRule.MARGIN);
        assertThat(area.minZ()).isEqualTo(-BuildAreaRule.MARGIN);
        assertThat(area.maxZ()).isEqualTo(19 + BuildAreaRule.MARGIN);
        assertThat(area.minY()).isEqualTo(64 - BuildAreaRule.BELOW);
        assertThat(area.maxY()).isEqualTo(66 + BuildAreaRule.ABOVE);
        assertThat(area.contains("arena", 5, 70, 5)).isTrue();
        assertThat(area.contains("arena", 40, 70, 5)).isFalse();
    }

    @Test
    @DisplayName("it never leaves the world's height")
    void clamped() {
        Box floor = Box.of("arena", 0, -60, 0, 5, 300, 5);

        Box area = rule.around(floor, -64, 319);

        assertThat(area.minY()).isEqualTo(-64);
        assertThat(area.maxY()).isEqualTo(319);
    }
}
