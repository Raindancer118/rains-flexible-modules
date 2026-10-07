package de.raindancer.modules.playerutils.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpeedRuleTest {

    private final SpeedRule rule = new SpeedRule();

    @Test
    @DisplayName("auto means flying speed while flying, walking speed otherwise")
    void auto() {
        assertThat(rule.kind("auto", true)).isEqualTo(SpeedRule.Kind.FLY);
        assertThat(rule.kind("auto", false)).isEqualTo(SpeedRule.Kind.WALK);
        assertThat(rule.kind("both", false)).isEqualTo(SpeedRule.Kind.BOTH);
        assertThat(rule.kind("walk", true)).isEqualTo(SpeedRule.Kind.WALK);
    }

    @Test
    @DisplayName("the game's raw speed read back as the level it came from")
    void level() {
        assertThat(rule.walkLevel(0.2f)).isEqualTo(1);
        assertThat(rule.walkLevel(0.6f)).isEqualTo(3);
        assertThat(rule.flyLevel(0.1f)).isEqualTo(1);
        assertThat(rule.flyLevel(1.0f)).isEqualTo(10);
    }
}
