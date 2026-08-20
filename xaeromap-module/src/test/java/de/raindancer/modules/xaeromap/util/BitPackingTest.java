package de.raindancer.modules.xaeromap.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That a region's packed entries are packed the way the client unpacks them.
 *
 * <p>Every failure here is invisible from the server: the packet goes out, the client accepts it, and
 * the map draws the wrong claims — or the client's own {@code SimpleBitStorage} throws on the array
 * length and drops the region without a word.
 */
class BitPackingTest {

    @Test
    @DisplayName("the bit width is one of the widths the mod's own storage produces")
    void widthsAreTheOnesTheModUses() {
        // Palette sizes include the leading "nobody" slot, so a region with one claim in it is 2.
        assertThat(BitPacking.bitsFor(1)).isEqualTo(1);
        assertThat(BitPacking.bitsFor(2)).isEqualTo(1);
        assertThat(BitPacking.bitsFor(3)).isEqualTo(2);
        assertThat(BitPacking.bitsFor(4)).isEqualTo(2);
        assertThat(BitPacking.bitsFor(5)).isEqualTo(4);
        assertThat(BitPacking.bitsFor(16)).isEqualTo(4);
        assertThat(BitPacking.bitsFor(17)).isEqualTo(6);
        assertThat(BitPacking.bitsFor(256)).isEqualTo(8);
        assertThat(BitPacking.bitsFor(1024)).isEqualTo(10);
        assertThat(BitPacking.bitsFor(1025)).isEqualTo(11);
    }

    @Test
    @DisplayName("every width between 1 and 11 is 1, 11, or an even number")
    void nothingOddInBetween() {
        for (int palette = 1; palette <= 4096; palette++) {
            int bits = BitPacking.bitsFor(palette);
            assertThat(bits == 1 || bits == 11 || bits % 2 == 0)
                    .as("a palette of %d asked for %d bits, a width the mod never produces and its "
                            + "reader has therefore never been run against", palette, bits)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("values never straddle two longs")
    void valuesStayInsideOneLong() {
        // 64 / 6 is ten with four bits left over, and those four are left empty rather than holding
        // the low end of the eleventh value. Packed tightly, everything past the tenth reads as noise.
        assertThat(BitPacking.valuesPerLong(6)).isEqualTo(10);
        assertThat(BitPacking.valuesPerLong(11)).isEqualTo(5);
        assertThat(BitPacking.valuesPerLong(1)).isEqualTo(64);
    }

    @Test
    @DisplayName("the array is exactly the length the client's constructor demands")
    void theArrayLengthIsExact() {
        assertThat(BitPacking.longsFor(1, 1024)).isEqualTo(16);
        assertThat(BitPacking.longsFor(2, 1024)).isEqualTo(32);
        assertThat(BitPacking.longsFor(4, 1024)).isEqualTo(64);
        // 1024 / 10 is 102.4 — so 103 longs, the last one only partly used. A length of 102 makes the
        // client throw and drop the region silently.
        assertThat(BitPacking.longsFor(6, 1024)).isEqualTo(103);
        assertThat(BitPacking.longsFor(11, 1024)).isEqualTo(205);
    }

    @Test
    @DisplayName("what was written into an entry is what comes back out of it")
    void entriesRoundTrip() {
        for (int bits : new int[] { 1, 2, 4, 6, 8, 10, 11 }) {
            long[] store = BitPacking.emptyStore(bits, BitPacking.REGION_ENTRIES);
            int highest = (1 << bits) - 1;
            for (int index = 0; index < BitPacking.REGION_ENTRIES; index++) {
                BitPacking.set(store, bits, index, index % (highest + 1));
            }
            for (int index = 0; index < BitPacking.REGION_ENTRIES; index++) {
                assertThat(BitPacking.get(store, bits, index))
                        .as("entry %d at %d bits", index, bits)
                        .isEqualTo(index % (highest + 1));
            }
        }
    }

    @Test
    @DisplayName("writing one entry leaves its neighbours alone")
    void neighboursSurvive() {
        long[] store = BitPacking.emptyStore(4, BitPacking.REGION_ENTRIES);
        BitPacking.set(store, 4, 7, 15);
        BitPacking.set(store, 4, 8, 3);
        BitPacking.set(store, 4, 8, 9);

        assertThat(BitPacking.get(store, 4, 7)).isEqualTo(15);
        assertThat(BitPacking.get(store, 4, 8)).isEqualTo(9);
        assertThat(BitPacking.get(store, 4, 6)).isZero();
        assertThat(BitPacking.get(store, 4, 9)).isZero();
    }

    @Test
    @DisplayName("an untouched store is every entry unclaimed")
    void emptyMeansUnclaimed() {
        long[] store = BitPacking.emptyStore(4, BitPacking.REGION_ENTRIES);

        for (long cell : store) {
            assertThat(cell)
                    .as("palette slot 0 is 'nobody', so an all-zero store has to mean an empty region")
                    .isZero();
        }
    }
}
