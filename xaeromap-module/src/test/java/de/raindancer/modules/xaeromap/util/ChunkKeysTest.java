package de.raindancer.modules.xaeromap.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That a chunk key here means the same thing it means in claims-module, and that a chunk lands in the
 * region slot the mod reads it out of.
 */
class ChunkKeysTest {

    @Test
    @DisplayName("the encoding is the one claims-module and Bukkit both use")
    void itIsBukkitsOwnEncoding() {
        for (int x : new int[] { 0, 1, -1, 31, -32, 1_000_000, -1_000_000 }) {
            for (int z : new int[] { 0, 1, -1, 31, -32, 1_000_000, -1_000_000 }) {
                long key = ChunkKeys.chunk(x, z);
                assertThat(ChunkKeys.chunkX(key)).as("x of (%d, %d)", x, z).isEqualTo(x);
                assertThat(ChunkKeys.chunkZ(key)).as("z of (%d, %d)", x, z).isEqualTo(z);
                assertThat(key)
                        .as("claims-module hands over keys made its own way; a second encoding here "
                                + "is a map lookup that silently misses")
                        .isEqualTo((long) z << 32 | x & 0xFFFFFFFFL);
            }
        }
    }

    @Test
    @DisplayName("a region holds 32 by 32 chunks, negative coordinates included")
    void regionsAreThirtyTwoWide() {
        assertThat(ChunkKeys.regionOf(ChunkKeys.chunk(0, 0))).isEqualTo(ChunkKeys.chunk(0, 0));
        assertThat(ChunkKeys.regionOf(ChunkKeys.chunk(31, 31))).isEqualTo(ChunkKeys.chunk(0, 0));
        assertThat(ChunkKeys.regionOf(ChunkKeys.chunk(32, 0))).isEqualTo(ChunkKeys.chunk(1, 0));
        assertThat(ChunkKeys.regionOf(ChunkKeys.chunk(-1, -1)))
                .as("an arithmetic shift, not a division — -1/32 is 0, which would put the chunk "
                        + "west of spawn in the region east of it")
                .isEqualTo(ChunkKeys.chunk(-1, -1));
        assertThat(ChunkKeys.regionOf(ChunkKeys.chunk(-32, -32))).isEqualTo(ChunkKeys.chunk(-1, -1));
        assertThat(ChunkKeys.regionOf(ChunkKeys.chunk(-33, -33))).isEqualTo(ChunkKeys.chunk(-2, -2));
    }

    @Test
    @DisplayName("a chunk's slot in its region is (x << 5) | z, and every slot is used once")
    void everyChunkHasItsOwnSlot() {
        boolean[] taken = new boolean[1024];
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                int index = ChunkKeys.indexInRegion(ChunkKeys.chunk(x, z));
                assertThat(index).isBetween(0, 1023);
                assertThat(taken[index])
                        .as("two chunks in slot %d — the map would draw one over the other", index)
                        .isFalse();
                taken[index] = true;
                assertThat(index)
                        .as("swapping the halves draws every claim mirrored across the diagonal")
                        .isEqualTo(x << 5 | z);
            }
        }
    }

    @Test
    @DisplayName("a chunk west of spawn lands in its region's slot, not out of bounds")
    void negativeChunksIndexInsideTheirRegion() {
        int index = ChunkKeys.indexInRegion(ChunkKeys.chunk(-1, -1));

        assertThat(index).isEqualTo(31 << 5 | 31);
    }

    @Test
    @DisplayName("a block column is in the chunk containing it")
    void blocksMapToTheirChunk() {
        assertThat(ChunkKeys.ofBlock(0, 0)).isEqualTo(ChunkKeys.chunk(0, 0));
        assertThat(ChunkKeys.ofBlock(15, 15)).isEqualTo(ChunkKeys.chunk(0, 0));
        assertThat(ChunkKeys.ofBlock(16, 0)).isEqualTo(ChunkKeys.chunk(1, 0));
        assertThat(ChunkKeys.ofBlock(-1, -1)).isEqualTo(ChunkKeys.chunk(-1, -1));
    }
}
