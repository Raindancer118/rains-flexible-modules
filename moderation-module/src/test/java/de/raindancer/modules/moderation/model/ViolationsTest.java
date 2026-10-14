package de.raindancer.modules.moderation.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ViolationsTest {

    private final AtomicLong clock = new AtomicLong(1_000_000);
    private final Violations violations = new Violations(clock::get);

    @Test
    @DisplayName("flags add up per check, and the total is the sum")
    void addsUp() {
        violations.add(CheckType.GRIM_MOVEMENT, 1);
        violations.add(CheckType.GRIM_MOVEMENT, 2);
        violations.add(CheckType.GRIM_COMBAT, 1.5);

        assertThat(violations.level(CheckType.GRIM_MOVEMENT)).isCloseTo(3, within(1e-9));
        assertThat(violations.level(CheckType.GRIM_COMBAT)).isCloseTo(1.5, within(1e-9));
        assertThat(violations.level(CheckType.GRIM_WORLD)).isZero();
        assertThat(violations.total()).isCloseTo(4.5, within(1e-9));
    }

    @Test
    @DisplayName("a level decays by the check's own rate per minute, and never below zero")
    void decays() {
        violations.add(CheckType.GRIM_MOVEMENT, 10);
        clock.addAndGet(60_000);
        assertThat(violations.level(CheckType.GRIM_MOVEMENT)).isCloseTo(10 - CheckType.GRIM_MOVEMENT.decayPerMinute(), within(1e-6));

        clock.addAndGet(60 * 60_000L);
        assertThat(violations.level(CheckType.GRIM_MOVEMENT)).isZero();
        assertThat(violations.total()).isZero();
    }

    @Test
    @DisplayName("adding after a pause decays first, then adds")
    void decaysBeforeAdding() {
        violations.add(CheckType.GRIM_MOVEMENT, 6);
        clock.addAndGet(30_000);
        double after = violations.add(CheckType.GRIM_MOVEMENT, 1);
        assertThat(after).isCloseTo(6 - CheckType.GRIM_MOVEMENT.decayPerMinute() / 2 + 1, within(1e-6));
    }

    @Test
    @DisplayName("reset forgets one check or all of them")
    void resets() {
        violations.add(CheckType.GRIM_MOVEMENT, 3);
        violations.add(CheckType.GRIM_WORLD, 3);
        violations.reset(CheckType.GRIM_MOVEMENT);
        assertThat(violations.level(CheckType.GRIM_MOVEMENT)).isZero();
        assertThat(violations.level(CheckType.GRIM_WORLD)).isPositive();
        violations.resetAll();
        assertThat(violations.total()).isZero();
    }

    @Test
    @DisplayName("the snapshot lists only checks with a level, highest first")
    void snapshot() {
        violations.add(CheckType.GRIM_MOVEMENT, 1);
        violations.add(CheckType.GRIM_COMBAT, 5);
        assertThat(violations.snapshot().keySet()).containsExactly(CheckType.GRIM_COMBAT, CheckType.GRIM_MOVEMENT);
    }

    @Test
    @DisplayName("a reduction after a kick takes some off but leaves the record")
    void scaledDown() {
        violations.add(CheckType.GRIM_MOVEMENT, 40);
        violations.scale(CheckType.GRIM_MOVEMENT, 0.5);
        assertThat(violations.level(CheckType.GRIM_MOVEMENT)).isCloseTo(20, within(1e-9));
    }
}
