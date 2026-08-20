package de.raindancer.modules.xaeromap.model;

import de.raindancer.modules.xaeromap.util.BitPacking;
import de.raindancer.modules.xaeromap.util.ChunkKeys;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One 32 × 32 patch of chunks, on its way to being a region packet.
 *
 * <p>A region is a palette plus 1024 indices into it. Slot 0 of that palette is always "nobody" — the
 * mod's own reader hard-requires it and throws if it is anything else — so a claim added to the palette
 * lands at slot <em>n + 1</em> while the palette array on the wire holds it at <em>n</em>. Getting that
 * off by one draws every claim as its neighbour, which is the kind of wrong that looks right.
 *
 * <p>Built once and read once: {@link #encode()} closes it.
 */
public final class RegionPage {

    private final int regionX;
    private final int regionZ;
    /** Claim sync index → its slot in the palette on the wire (0-based), in insertion order. */
    private final Map<Integer, Integer> slots = new LinkedHashMap<>();
    private final int[] entries = new int[BitPacking.REGION_ENTRIES];

    public RegionPage(int regionX, int regionZ) {
        this.regionX = regionX;
        this.regionZ = regionZ;
    }

    public static RegionPage of(long regionKey) {
        return new RegionPage(ChunkKeys.regionX(regionKey), ChunkKeys.regionZ(regionKey));
    }

    public int regionX() {
        return regionX;
    }

    public int regionZ() {
        return regionZ;
    }

    /** Puts one chunk of this region in a claim's hands. */
    public void put(long chunkKey, int claimSyncIndex) {
        int slot = slots.computeIfAbsent(claimSyncIndex, index -> slots.size());
        entries[ChunkKeys.indexInRegion(chunkKey)] = slot + 1;
    }

    public boolean isEmpty() {
        return slots.isEmpty();
    }

    /** How many distinct claims reach into this region. */
    public int claimCount() {
        return slots.size();
    }

    /** How many of this region's 1024 chunks are claimed — what a send budget is counted in. */
    public int chunkCount() {
        int claimed = 0;
        for (int entry : entries) {
            if (entry != 0) {
                claimed++;
            }
        }
        return claimed;
    }

    /** The finished region packet, ready to send. */
    public byte[] encode() {
        int[] palette = new int[slots.size()];
        List<Integer> ordered = new ArrayList<>(slots.keySet());
        for (int i = 0; i < ordered.size(); i++) {
            palette[i] = ordered.get(i);
        }
        int bits = BitPacking.bitsFor(palette.length + 1);
        long[] data = BitPacking.emptyStore(bits, BitPacking.REGION_ENTRIES);
        for (int index = 0; index < entries.length; index++) {
            if (entries[index] != 0) {
                BitPacking.set(data, bits, index, entries[index]);
            }
        }
        return OpacPackets.region(regionX, regionZ, palette, bits, data);
    }
}
