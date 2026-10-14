package de.raindancer.modules.enchants.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BoostChargesTest {

    private static final long MINUTE = 60_000L;

    @Test
    @DisplayName("an elytra starts full, spends a charge per boost, and refuses once empty")
    void spend() {
        BoostCharges charges = BoostCharges.full(2);
        assertThat(charges.take(0, 2, MINUTE)).isTrue();
        assertThat(charges.take(1, 2, MINUTE)).isTrue();
        assertThat(charges.take(2, 2, MINUTE)).isFalse();
        assertThat(charges.charges()).isZero();
    }

    @Test
    @DisplayName("charges come back one at a time, counted from the first one spent")
    void recharge() {
        BoostCharges charges = BoostCharges.full(3);
        charges.take(0, 3, MINUTE);
        charges.take(10_000, 3, MINUTE);
        charges.update(MINUTE - 1, 3, MINUTE);
        assertThat(charges.charges()).isEqualTo(1);
        charges.update(MINUTE, 3, MINUTE);
        assertThat(charges.charges()).isEqualTo(2);
        assertThat(charges.millisToNext(MINUTE)).isEqualTo(MINUTE);
        charges.update(10 * MINUTE, 3, MINUTE);
        assertThat(charges.charges()).as("never above the level").isEqualTo(3);
        assertThat(charges.millisToNext(10 * MINUTE)).isZero();
    }

    @Test
    @DisplayName("a weaker elytra put on caps the charges at its own level")
    void capped() {
        BoostCharges charges = BoostCharges.full(3);
        charges.update(0, 1, MINUTE);
        assertThat(charges.charges()).isEqualTo(1);
    }

    @Test
    @DisplayName("a clock that went backwards (another server) never hands out free charges")
    void clockBack() {
        BoostCharges charges = BoostCharges.of(0, 5 * MINUTE);
        charges.update(MINUTE, 2, MINUTE);
        assertThat(charges.charges()).isZero();
        assertThat(charges.millisToNext(MINUTE)).as("waits at most one recharge").isEqualTo(MINUTE);
    }
}
