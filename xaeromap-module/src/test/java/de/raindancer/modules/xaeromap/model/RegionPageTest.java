package de.raindancer.modules.xaeromap.model;

import de.raindancer.modules.xaeromap.util.BitPacking;
import de.raindancer.modules.xaeromap.util.ChunkKeys;
import de.raindancer.modules.xaeromap.util.Nbt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That a region packet says which claim holds which chunk — the one thing in this module that is
 * arithmetic all the way down and wrong in a way nobody would notice from the server side.
 */
class RegionPageTest {

    @Test
    @DisplayName("palette slot 0 is nobody, so the first claim is stored as 1")
    void theFirstClaimIsSlotOne() {
        RegionPage page = new RegionPage(0, 0);
        page.put(ChunkKeys.chunk(5, 6), 42);

        Map<String, Object> read = Nbt.readPayload(page.encode());
        int[] palette = (int[]) read.get("p");
        long[] data = (long[]) read.get("d");
        int bits = (Byte) read.get("b");

        assertThat(palette)
                .as("the palette on the wire holds the claim at index 0; the *storage* points at 1, "
                        + "because slot 0 of what the client builds is the empty one")
                .containsExactly(42);
        assertThat(BitPacking.get(data, bits, ChunkKeys.indexInRegion(ChunkKeys.chunk(5, 6))))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("chunks nobody claimed stay at zero")
    void unclaimedChunksAreZero() {
        RegionPage page = new RegionPage(0, 0);
        page.put(ChunkKeys.chunk(0, 0), 1);

        Map<String, Object> read = Nbt.readPayload(page.encode());
        long[] data = (long[]) read.get("d");
        int bits = (Byte) read.get("b");

        assertThat(BitPacking.get(data, bits, ChunkKeys.indexInRegion(ChunkKeys.chunk(1, 0))))
                .isZero();
        assertThat(page.chunkCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("two claims in one region each keep their own slot")
    void twoClaimsGetTwoSlots() {
        RegionPage page = new RegionPage(2, -3);
        page.put(ChunkKeys.chunk(64, -96), 7);
        page.put(ChunkKeys.chunk(65, -96), 8);
        page.put(ChunkKeys.chunk(66, -96), 7);

        Map<String, Object> read = Nbt.readPayload(page.encode());
        int[] palette = (int[]) read.get("p");
        long[] data = (long[]) read.get("d");
        int bits = (Byte) read.get("b");

        assertThat(palette).containsExactly(7, 8);
        assertThat(page.claimCount()).isEqualTo(2);
        assertThat(page.chunkCount()).isEqualTo(3);
        assertThat(BitPacking.get(data, bits, ChunkKeys.indexInRegion(ChunkKeys.chunk(64, -96))))
                .isEqualTo(1);
        assertThat(BitPacking.get(data, bits, ChunkKeys.indexInRegion(ChunkKeys.chunk(65, -96))))
                .isEqualTo(2);
        assertThat(BitPacking.get(data, bits, ChunkKeys.indexInRegion(ChunkKeys.chunk(66, -96))))
                .as("the same claim twice must not take a second palette slot")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the region names the region, not one of its chunks")
    void theCoordinatesAreTheRegionsOwn() {
        RegionPage page = RegionPage.of(ChunkKeys.regionOf(ChunkKeys.chunk(-33, 70)));

        Map<String, Object> read = Nbt.readPayload(page.encode());

        assertThat(read.get("x")).isEqualTo(-2);
        assertThat(read.get("z")).isEqualTo(2);
        assertThat(page.regionX()).isEqualTo(-2);
        assertThat(page.regionZ()).isEqualTo(2);
    }

    @Test
    @DisplayName("a region full of different claims still packs into a readable width")
    void aBusyRegionStaysReadable() {
        RegionPage page = new RegionPage(0, 0);
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                page.put(ChunkKeys.chunk(x, z), x * 32 + z + 1);
            }
        }

        Map<String, Object> read = Nbt.readPayload(page.encode());
        int bits = (Byte) read.get("b");
        long[] data = (long[]) read.get("d");

        assertThat(page.claimCount()).isEqualTo(1024);
        assertThat(bits)
                .as("1025 palette entries including the empty one needs the mod's widest width")
                .isEqualTo(11);
        assertThat(data)
                .as("a short array makes the client's own SimpleBitStorage throw, and the region "
                        + "vanishes without a word on either side")
                .hasSize(BitPacking.longsFor(11, 1024));
        assertThat(BitPacking.get(data, bits, ChunkKeys.indexInRegion(ChunkKeys.chunk(31, 31))))
                .isEqualTo(1024);
    }

    @Test
    @DisplayName("an empty region is empty rather than a region of claim number one")
    void anEmptyRegionClaimsNothing() {
        RegionPage page = new RegionPage(0, 0);

        Map<String, Object> read = Nbt.readPayload(page.encode());

        assertThat(page.isEmpty()).isTrue();
        assertThat((int[]) read.get("p")).isEmpty();
        for (long cell : (long[]) read.get("d")) {
            assertThat(cell).isZero();
        }
    }
}
