package de.raindancer.modules.xaeromap.util;

/**
 * Minecraft's own packed-array layout, written from outside the server jar.
 *
 * <p>A claim region on the wire is 1024 palette indices packed into {@code long}s exactly the way
 * {@code SimpleBitStorage} packs them, because that is the class the client hands them to. Three
 * details are not negotiable, and each one produces a different failure:
 *
 * <ul>
 *   <li><b>Values never straddle two longs.</b> {@code 64 / bits} values go in each long and the
 *       remaining high bits are left empty. Packing them tightly instead reads back as noise —
 *       every chunk in the region shows the wrong owner.</li>
 *   <li><b>The array length is exact.</b> {@code SimpleBitStorage}'s constructor throws when the
 *       {@code long[]} is not {@code ceil(1024 / valuesPerLong)} long, and the client's decoder
 *       turns that into a dropped packet — the region silently never appears.</li>
 *   <li><b>The bit width is one of the widths the mod expects.</b> {@link #bitsFor} is Open Parties
 *       and Claims' own rule, not vanilla's: 1, then even numbers, then 11.</li>
 * </ul>
 *
 * <p>Pure arithmetic, no server, so it can be — and is — tested against known layouts rather than
 * tried once on a live client.
 */
public final class BitPacking {

    /** Chunks in a claim region: 32 × 32. */
    public static final int REGION_ENTRIES = 1024;

    private BitPacking() {
    }

    /**
     * How many bits one entry takes for a palette of this size.
     *
     * @param paletteSize the palette including its leading empty slot, so at least 1
     */
    public static int bitsFor(int paletteSize) {
        int needed = ceilLog2(Math.max(1, paletteSize));
        if (needed <= 1) {
            return 1;
        }
        if (needed >= 11) {
            return 11;
        }
        // Every width between is rounded up to a multiple of two, which is the shape the mod's own
        // storage grows in. A width it never produces is a width its reader has not been run against.
        return (needed + 1) / 2 * 2;
    }

    /** {@code ceil(log2(value))}, the same answer {@code Mth.ceillog2} gives. */
    public static int ceilLog2(int value) {
        if (value <= 1) {
            return 0;
        }
        return 32 - Integer.numberOfLeadingZeros(value - 1);
    }

    /** How many values share one long at this width. */
    public static int valuesPerLong(int bits) {
        require(bits);
        return 64 / bits;
    }

    /** The exact length the {@code long[]} must have. */
    public static int longsFor(int bits, int entries) {
        int perLong = valuesPerLong(bits);
        return (entries + perLong - 1) / perLong;
    }

    /** An all-zero store — every entry "no claim" — of the right length. */
    public static long[] emptyStore(int bits, int entries) {
        return new long[longsFor(bits, entries)];
    }

    /** Writes one entry, leaving every other entry alone. */
    public static void set(long[] store, int bits, int index, int value) {
        require(bits);
        int perLong = valuesPerLong(bits);
        int cell = index / perLong;
        int offset = (index - cell * perLong) * bits;
        long mask = (1L << bits) - 1L;
        store[cell] = store[cell] & ~(mask << offset) | (value & mask) << offset;
    }

    /** Reads one entry back. Here so a test can prove a store says what it was told to say. */
    public static int get(long[] store, int bits, int index) {
        require(bits);
        int perLong = valuesPerLong(bits);
        int cell = index / perLong;
        int offset = (index - cell * perLong) * bits;
        long mask = (1L << bits) - 1L;
        return (int) (store[cell] >>> offset & mask);
    }

    private static void require(int bits) {
        if (bits < 1 || bits > 32) {
            throw new IllegalArgumentException("a bit width of " + bits + " is not a width anything reads");
        }
    }
}
