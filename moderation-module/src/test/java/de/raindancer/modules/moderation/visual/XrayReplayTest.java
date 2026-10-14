package de.raindancer.modules.moderation.visual;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class XrayReplayTest {

    private static final List<int[]> TRAIL = List.of(new int[]{100, 12, 100, -1}, new int[]{101, 12, 100, -1},
            new int[]{102, 11, 100, 3}, new int[]{103, 11, 101, -2});

    @Test
    @DisplayName("standing near their digging: drawn where you are, no trip")
    void near() {
        assertThat(XrayReplay.whereToStand(TRAIL, "world", "world", 110, 20, 105)).isEmpty();
    }

    @Test
    @DisplayName("far away or in another world: taken to the last block they dug, which is open tunnel")
    void far() {
        assertThat(XrayReplay.whereToStand(TRAIL, "world", "world", 900, 70, 900)).hasValueSatisfying(at ->
                assertThat(at).containsExactly(103, 11, 101));
        assertThat(XrayReplay.whereToStand(TRAIL, "world", "world_nether", 100, 12, 100)).isPresent();
    }

    @Test
    @DisplayName("nothing dug: nowhere to go")
    void nothing() {
        assertThat(XrayReplay.whereToStand(List.of(), "", "world", 0, 0, 0)).isEmpty();
    }
}
