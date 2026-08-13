package de.raindancer.modules.mannequin.model;

import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PlayerTallyTest {

    private static final UUID PLAYER = UUID.randomUUID();

    @Test
    @DisplayName("an empty tally has nothing, but is still a real tally for this player")
    void emptyTallyHasNothing() {
        PlayerTally tally = PlayerTally.empty(PLAYER);

        assertThat(tally.player()).isEqualTo(PLAYER);
        assertThat(tally.totalDamage()).isZero();
        assertThat(tally.totalHits()).isZero();
        assertThat(tally.weaponCount()).isZero();
    }

    @Test
    @DisplayName("hits with different weapons are kept apart, not merged into one number")
    void differentWeaponsStayApart() {
        PlayerTally tally = PlayerTally.empty(PLAYER)
                .withHit(Material.NETHERITE_SWORD, null, 15.0)
                .withHit(Material.TRIDENT, null, 9.0)
                .withHit(Material.NETHERITE_SWORD, null, 5.0);

        assertThat(tally.weaponCount()).isEqualTo(2);
        assertThat(tally.totalHits()).isEqualTo(3);
        assertThat(tally.totalDamage()).isEqualTo(29.0);
    }

    @Test
    @DisplayName("the ranked list puts the heaviest-hitting weapon first")
    void rankedByTotalDamagePutsTheBiggestFirst() {
        PlayerTally tally = PlayerTally.empty(PLAYER)
                .withHit(Material.WOODEN_SWORD, null, 2.0)
                .withHit(Material.NETHERITE_SWORD, null, 40.0)
                .withHit(Material.TRIDENT, null, 10.0);

        assertThat(tally.rankedByTotalDamage())
                .extracting(WeaponTally::weapon)
                .containsExactly(Material.NETHERITE_SWORD, Material.TRIDENT, Material.WOODEN_SWORD);
    }
}
