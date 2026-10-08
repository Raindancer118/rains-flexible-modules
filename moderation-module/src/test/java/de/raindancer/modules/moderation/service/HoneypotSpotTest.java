package de.raindancer.modules.moderation.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bait must be spread evenly over the shell, because the expected number of hits assumes it. A spot
 * outside the world is a miss, never moved to the edge — moving it piled bait into the bottom layers,
 * exactly where honest players strip-mine for diamonds.
 */
class HoneypotSpotTest {

    @Test
    @DisplayName("a spot below the world is refused, not clamped onto the bottom layer")
    void noClamping() {
        Random random = new Random(1);
        int bottom = -64;
        int atBottom = 0;
        int inside = 0;
        int refused = 0;
        for (int i = 0; i < 20_000; i++) {
            Optional<int[]> spot = HoneypotService.spotInShell(random, 0, -59, 0, 20, bottom, 320);
            if (spot.isEmpty()) {
                refused++;
                continue;
            }
            inside++;
            assertThat(spot.get()[1]).isGreaterThan(bottom);
            if (spot.get()[1] == bottom + 1) {
                atBottom++;
            }
        }
        assertThat(refused).as("about a third of the shell is below the world").isGreaterThan(4000);
        // With 40 possible layers, one layer should hold roughly a fortieth of the spots, never a pile.
        assertThat((double) atBottom / inside).isLessThan(0.06);
    }

    @Test
    @DisplayName("every spot lies in the shell between the inner and outer radius")
    void inShell() {
        Random random = new Random(2);
        for (int i = 0; i < 5000; i++) {
            HoneypotService.spotInShell(random, 100, 30, -100, 20, -64, 320).ifPresent(spot -> {
                double d = Math.sqrt(Math.pow(spot[0] - 100, 2) + Math.pow(spot[1] - 30, 2) + Math.pow(spot[2] + 100, 2));
                assertThat(d).isBetween(6.0, 20.0);
            });
        }
    }
}
