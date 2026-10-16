package de.raindancer.modules.ctf.rules;

import de.raindancer.modules.ctf.model.Building;
import de.raindancer.modules.ctf.model.Point;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("what players may change on the map")
class BuildRuleTest {

    private final BuildRule rule = new BuildRule();
    private final List<Point> keepClear = List.of(Point.of(0, 10, 0), Point.of(100, 10, 0));
    private final BuildRule.Area area = new BuildRule.Area(-10, 0, -10, 110, 40, 10);

    @Test
    @DisplayName("building off: nothing, anywhere")
    void off() {
        assertThat(rule.mayPlace(Building.NONE, 50, 10, 0, area, keepClear, 4)).isFalse();
        assertThat(rule.mayBreak(Building.NONE, false, 50, 10, 0, area, keepClear, 4)).isFalse();
    }

    @Test
    @DisplayName("own blocks: place anywhere allowed, break only what was placed this round")
    void ownBlocks() {
        assertThat(rule.mayPlace(Building.OWN_BLOCKS, 50, 10, 0, area, keepClear, 4)).isTrue();
        assertThat(rule.mayBreak(Building.OWN_BLOCKS, true, 50, 10, 0, area, keepClear, 4)).isTrue();
        assertThat(rule.mayBreak(Building.OWN_BLOCKS, false, 50, 10, 0, area, keepClear, 4)).isFalse();
    }

    @Test
    @DisplayName("free: break the map too")
    void free() {
        assertThat(rule.mayBreak(Building.FREE, false, 50, 10, 0, area, keepClear, 4)).isTrue();
    }

    @Test
    @DisplayName("never near a flag or a spawn — nobody walls a flag in")
    void keepClear() {
        assertThat(rule.mayPlace(Building.FREE, 2, 11, 1, area, keepClear, 4)).isFalse();
        assertThat(rule.mayBreak(Building.FREE, true, 99, 10, 0, area, keepClear, 4)).isFalse();
        assertThat(rule.mayPlace(Building.FREE, 6, 10, 0, area, keepClear, 4)).isTrue();
    }

    @Test
    @DisplayName("never outside the map")
    void outside() {
        assertThat(rule.mayPlace(Building.FREE, 200, 10, 0, area, keepClear, 4)).isFalse();
        assertThat(rule.mayPlace(Building.FREE, 50, 41, 0, area, keepClear, 4)).isFalse();
    }

    @Test
    @DisplayName("spleef breaks anything breakable whatever the setting, but still never near a flag")
    void spleef() {
        assertThat(rule.mayBreakForSpleef(50, 10, 0, area, keepClear, 4)).isTrue();
        assertThat(rule.mayBreakForSpleef(0, 9, 0, area, keepClear, 4)).isFalse();
    }
}
