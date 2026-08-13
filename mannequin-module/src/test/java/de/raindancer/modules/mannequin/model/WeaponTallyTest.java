package de.raindancer.modules.mannequin.model;

import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeaponTallyTest {

    @Test
    @DisplayName("a first hit starts every number from that one hit")
    void firstHitStartsTheTally() {
        WeaponTally tally = WeaponTally.firstHit(Material.NETHERITE_SWORD, null, 12.0);

        assertThat(tally.hits()).isEqualTo(1);
        assertThat(tally.totalDamage()).isEqualTo(12.0);
        assertThat(tally.highestHit()).isEqualTo(12.0);
        assertThat(tally.averageDamage()).isEqualTo(12.0);
    }

    @Test
    @DisplayName("a later hit accumulates rather than replacing")
    void laterHitsAccumulate() {
        WeaponTally tally = WeaponTally.firstHit(Material.NETHERITE_SWORD, null, 10.0)
                .hit(null, 6.0)
                .hit(null, 20.0);

        assertThat(tally.hits()).isEqualTo(3);
        assertThat(tally.totalDamage()).isEqualTo(36.0);
        assertThat(tally.highestHit()).isEqualTo(20.0);
        assertThat(tally.averageDamage()).isEqualTo(12.0);
    }

    @Test
    @DisplayName("a weapon is required")
    void needsAWeapon() {
        assertThatThrownBy(() -> new WeaponTally(null, null, 1, 1.0, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("negative inputs are clamped rather than corrupting the tally")
    void negativeInputsAreClamped() {
        WeaponTally tally = new WeaponTally(Material.STICK, null, -5, -10.0, -3.0);

        assertThat(tally.hits()).isZero();
        assertThat(tally.totalDamage()).isZero();
        assertThat(tally.highestHit()).isZero();
    }

    @Test
    @DisplayName("an empty tally's average is zero, not a division by zero")
    void emptyTallyHasZeroAverage() {
        assertThat(new WeaponTally(Material.STICK, null, 0, 0.0, 0.0).averageDamage()).isZero();
    }
}
