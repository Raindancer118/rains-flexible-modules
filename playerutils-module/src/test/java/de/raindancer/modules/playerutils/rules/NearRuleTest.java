package de.raindancer.modules.playerutils.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Who is near, how far, which way — from where the viewer stands and faces. */
class NearRuleTest {

    private final NearRule rule = new NearRule();

    @Test
    @DisplayName("sorted by distance, only within the radius, only in the same world")
    void sorted() {
        NearRule.Spot me = new NearRule.Spot("me", "world", 0, 64, 0, 180);
        List<NearRule.Spot> others = List.of(
                new NearRule.Spot("far", "world", 300, 64, 0, 0),
                new NearRule.Spot("close", "world", 10, 64, 0, 0),
                new NearRule.Spot("middle", "world", 0, 64, 50, 0),
                new NearRule.Spot("nether", "world_nether", 1, 64, 1, 0));

        List<NearRule.Nearby> near = rule.near(me, others, 200);

        assertThat(near).extracting(NearRule.Nearby::name).containsExactly("close", "middle");
        assertThat(near.getFirst().distance()).isEqualTo(10);
    }

    @Test
    @DisplayName("the arrow points where they are relative to where the viewer looks")
    void arrows() {
        // Minecraft: yaw 180 faces north (-z), yaw 0 faces south (+z), yaw -90 faces east (+x).
        NearRule.Spot facingNorth = new NearRule.Spot("me", "world", 0, 64, 0, 180);

        assertThat(rule.arrow(facingNorth, new NearRule.Spot("n", "world", 0, 64, -10, 0))).isEqualTo("↑");
        assertThat(rule.arrow(facingNorth, new NearRule.Spot("s", "world", 0, 64, 10, 0))).isEqualTo("↓");
        assertThat(rule.arrow(facingNorth, new NearRule.Spot("e", "world", 10, 64, 0, 0))).isEqualTo("→");
        assertThat(rule.arrow(facingNorth, new NearRule.Spot("w", "world", -10, 64, 0, 0))).isEqualTo("←");
    }

    @Test
    @DisplayName("the radius is clamped to what the server allows")
    void radius() {
        assertThat(rule.radius(5000, 1000)).isEqualTo(1000);
        assertThat(rule.radius(0, 1000)).isEqualTo(1);
    }
}
