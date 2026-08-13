package de.raindancer.modules.mannequin.model;

import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LeaderboardTest {

    private static final UUID ALEX = UUID.randomUUID();
    private static final UUID STEVE = UUID.randomUUID();

    @Test
    @DisplayName("EMPTY has nobody, and is what a fresh mannequin starts with")
    void emptyHasNobody() {
        assertThat(Leaderboard.EMPTY.isEmpty()).isTrue();
        assertThat(Leaderboard.EMPTY.rankedByTotalDamage()).isEmpty();
    }

    @Test
    @DisplayName("hits from different players are kept apart, ranked by their own total")
    void ranksPlayersByTotalDamage() {
        Leaderboard board = Leaderboard.EMPTY
                .withHit(ALEX, Material.DIAMOND_AXE, null, 10.0)
                .withHit(STEVE, Material.NETHERITE_SWORD, null, 40.0)
                .withHit(ALEX, Material.DIAMOND_AXE, null, 5.0);

        assertThat(board.rankedByTotalDamage())
                .extracting(PlayerTally::player)
                .containsExactly(STEVE, ALEX);
        assertThat(board.byPlayer().get(ALEX).totalDamage()).isEqualTo(15.0);
        assertThat(board.byPlayer().get(STEVE).totalDamage()).isEqualTo(40.0);
    }

    @Test
    @DisplayName("a second hit from the same player builds on their existing tally, not a fresh one")
    void secondHitBuildsOnTheFirst() {
        Leaderboard board = Leaderboard.EMPTY
                .withHit(ALEX, Material.DIAMOND_AXE, null, 10.0)
                .withHit(ALEX, Material.DIAMOND_AXE, null, 10.0);

        assertThat(board.byPlayer().get(ALEX).byWeapon().get(Material.DIAMOND_AXE).hits()).isEqualTo(2);
    }
}
