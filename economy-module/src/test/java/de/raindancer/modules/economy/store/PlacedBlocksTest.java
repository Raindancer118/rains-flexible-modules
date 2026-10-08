package de.raindancer.modules.economy.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlacedBlocksTest {

    @Test
    @DisplayName("every position in a chunk packs to its own number, negative heights and world coordinates alike")
    void packing() {
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = -64; y < 320; y += 7) {
                    assertThat(seen.add(PlacedBlocks.pack(x, y, z))).isTrue();
                }
            }
        }
        assertThat(PlacedBlocks.pack(-1, 5, -16)).isEqualTo(PlacedBlocks.pack(15, 5, 0));
    }

    @Test
    @DisplayName("marking twice keeps one entry, and removing forgets exactly that one")
    void marks() {
        long a = PlacedBlocks.pack(1, 2, 3);
        long b = PlacedBlocks.pack(4, 5, 6);
        long[] marked = PlacedBlocks.with(PlacedBlocks.with(PlacedBlocks.with(null, a), a), b);
        assertThat(marked).containsExactly(a, b);
        assertThat(PlacedBlocks.without(marked, a)).containsExactly(b);
        assertThat(PlacedBlocks.contains(null, a)).isFalse();
    }
}
