package de.raindancer.modules.anticheat.rules;

import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CombatRuleTest {

    private final CombatRule rule = new CombatRule();

    /** A standing player's box with their feet at x, y, z. */
    private static BoundingBox playerAt(double x, double y, double z) {
        return new BoundingBox(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3);
    }

    private static Vector eyeAt(double x, double y, double z) {
        return new Vector(x, y + 1.62, z);
    }

    @Test
    @DisplayName("Bukkit's own direction maths: yaw 0 looks south (+z), yaw 90 west (-x), pitch 90 down")
    void directions() {
        assertThat(Geometry.direction(0, 0).getZ()).isCloseTo(1, within(1e-9));
        assertThat(Geometry.direction(90, 0).getX()).isCloseTo(-1, within(1e-9));
        assertThat(Geometry.direction(0, 90).getY()).isCloseTo(-1, within(1e-9));
    }

    @Test
    @DisplayName("distance is to the nearest face of the box, not the feet")
    void distanceToBox() {
        assertThat(Geometry.distance(new Vector(0, 1.62, 0), playerAt(0, 0, 3.3))).isCloseTo(3.0, within(1e-9));
        assertThat(Geometry.distance(new Vector(0, 1, 0), playerAt(0, 0, 0))).isZero();
    }

    @Test
    @DisplayName("three blocks to the box is in reach; four is not")
    void reach() {
        assertThat(rule.reach(List.of(eyeAt(0, 0, 0)), List.of(playerAt(0, 0, 3.3)), 3.0, 0.1).passed()).isTrue();
        Judgement far = rule.reach(List.of(eyeAt(0, 0, 0)), List.of(playerAt(0, 0, 4.3)), 3.0, 0.1);
        assertThat(far.failed()).isTrue();
        assertThat(far.offset()).isCloseTo(1.0, within(1e-6));
    }

    @Test
    @DisplayName("a target that was in reach a moment ago counts — lag goes to the player")
    void reachHistory() {
        List<BoundingBox> history = List.of(playerAt(0, 0, 4.5), playerAt(0, 0, 3.2));
        assertThat(rule.reach(List.of(eyeAt(0, 0, 0)), history, 3.0, 0.1).passed()).isTrue();
    }

    @Test
    @DisplayName("looking straight at the target hits its box; looking away does not")
    void hitbox() {
        List<Vector> eye = List.of(eyeAt(0, 0, 0));
        List<BoundingBox> target = List.of(playerAt(0, 0, 2.5));
        assertThat(rule.hitbox(eye, List.of(new CombatRule.Rotation(0, 0)), target, 0.1, 6).passed()).isTrue();
        Judgement away = rule.hitbox(eye, List.of(new CombatRule.Rotation(180, 0)), target, 0.1, 6);
        assertThat(away.failed()).isTrue();
        assertThat(away.offset()).isGreaterThan(150);
    }

    @Test
    @DisplayName("the look direction of the next tick counts too, since the hit is sent before the turn")
    void hitboxEitherRotation() {
        List<CombatRule.Rotation> rotations = List.of(new CombatRule.Rotation(90, 0), new CombatRule.Rotation(0, 10));
        assertThat(rule.hitbox(List.of(eyeAt(0, 0, 0)), rotations, List.of(playerAt(0, 0, 2.5)), 0.1, 6).passed()).isTrue();
    }

    @Test
    @DisplayName("yaw deltas wrap around")
    void yawDelta() {
        assertThat(Geometry.yawDelta(350, 10)).isCloseTo(20, within(1e-9));
        assertThat(Geometry.yawDelta(10, 350)).isCloseTo(-20, within(1e-9));
        assertThat(Geometry.yawDelta(0, 720)).isCloseTo(0, within(1e-9));
    }
}
