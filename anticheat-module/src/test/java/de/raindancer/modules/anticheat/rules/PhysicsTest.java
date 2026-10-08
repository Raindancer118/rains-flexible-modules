package de.raindancer.modules.anticheat.rules;

import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PhysicsTest {

    @Test
    @DisplayName("a vanilla jump: 0.42 up, then gravity and drag, peaking at about 1.25 blocks")
    void jumpArc() {
        double v = Physics.JUMP_VELOCITY;
        double height = 0;
        double peak = 0;
        for (int tick = 0; tick < 30; tick++) {
            height += v;
            peak = Math.max(peak, height);
            v = Physics.nextVertical(v, Physics.GRAVITY);
        }
        assertThat(peak).isCloseTo(1.2522, within(0.001));
        assertThat(Physics.nextVertical(0.42, Physics.GRAVITY)).isCloseTo(0.3332, within(1e-4));
    }

    @Test
    @DisplayName("falling settles at the vanilla terminal speed of about 3.92 blocks a tick")
    void terminal() {
        double v = 0;
        for (int tick = 0; tick < 1000; tick++) {
            v = Physics.nextVertical(v, Physics.GRAVITY);
        }
        assertThat(v).isCloseTo(-3.92, within(0.001));
    }

    @Test
    @DisplayName("sprinting on stone accelerates by 0.13 a tick and settles near 0.286")
    void groundSprint() {
        double accel = Physics.groundAcceleration(0.13, Physics.DEFAULT_FRICTION);
        assertThat(accel).isCloseTo(0.13, within(1e-6));
        double v = 0;
        for (int tick = 0; tick < 100; tick++) {
            v = v * Physics.DEFAULT_FRICTION * Physics.AIR_FRICTION + accel;
        }
        assertThat(v).isCloseTo(0.2864, within(0.001));
    }

    @Test
    @DisplayName("jump power is the attribute times the block's factor, plus a tenth per jump boost level")
    void jumpPower() {
        assertThat(Physics.jumpVelocity(0.42, 0, 1.0)).isCloseTo(0.42, within(1e-9));
        assertThat(Physics.jumpVelocity(0.42, 2, 1.0)).isCloseTo(0.62, within(1e-9));
        assertThat(Physics.jumpVelocity(0.42, 0, 0.5)).isCloseTo(0.21, within(1e-9));
    }

    @Test
    @DisplayName("ice is slippery, slime a little, everything else is 0.6")
    void friction() {
        assertThat(Physics.friction(Material.ICE)).isEqualTo(0.98);
        assertThat(Physics.friction(Material.BLUE_ICE)).isEqualTo(0.989);
        assertThat(Physics.friction(Material.SLIME_BLOCK)).isEqualTo(0.8);
        assertThat(Physics.friction(Material.STONE)).isEqualTo(0.6);
        assertThat(Physics.friction(Material.AIR)).isEqualTo(0.6);
    }

    @Test
    @DisplayName("several ticks of free fall add up the way vanilla adds them")
    void sumOfTicks() {
        double first = Physics.nextVertical(0, Physics.GRAVITY);
        double second = Physics.nextVertical(first, Physics.GRAVITY);
        assertThat(Physics.travelled(first, 2, Physics.GRAVITY)).isCloseTo(first + second, within(1e-12));
    }

    @Test
    @DisplayName("feet passing a block top while still rising are not standing on it; a step up is")
    void groundOnlyWhenNotRising() {
        assertThat(Physics.standing(true, 0.248, false, Physics.STEP_HEIGHT)).isFalse();
        assertThat(Physics.standing(true, 0.0, true, Physics.STEP_HEIGHT)).isTrue();
        assertThat(Physics.standing(true, -0.3, false, Physics.STEP_HEIGHT)).isTrue();
        assertThat(Physics.standing(true, 0.5, true, Physics.STEP_HEIGHT)).isTrue();
        assertThat(Physics.standing(false, -0.1, true, Physics.STEP_HEIGHT)).isFalse();
    }
}
