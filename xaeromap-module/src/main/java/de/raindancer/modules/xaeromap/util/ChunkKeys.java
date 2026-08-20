package de.raindancer.modules.xaeromap.util;

/**
 * Chunk and region coordinates packed into a {@code long}, in the one encoding this module uses.
 *
 * <p>The same encoding {@code Chunk#getChunkKey()} and {@code ClaimShape.chunkKey} use — z in the
 * high word, x in the low one — so a key from claims-module can be handed straight to this without a
 * conversion nobody would remember to write. There is deliberately no second encoding here: two ways
 * of packing a coordinate pair is a map lookup that silently misses.
 */
public final class ChunkKeys {

    /** Chunks along one edge of a claim region. */
    public static final int REGION_SIZE = 32;

    private ChunkKeys() {
    }

    public static long chunk(int chunkX, int chunkZ) {
        return (long) chunkZ << 32 | chunkX & 0xFFFFFFFFL;
    }

    public static int chunkX(long key) {
        return (int) (key & 0xFFFFFFFFL);
    }

    public static int chunkZ(long key) {
        return (int) (key >> 32);
    }

    /** The region a chunk belongs to, as its own key. */
    public static long regionOf(long chunkKey) {
        return chunk(chunkX(chunkKey) >> 5, chunkZ(chunkKey) >> 5);
    }

    public static int regionX(long regionKey) {
        return chunkX(regionKey);
    }

    public static int regionZ(long regionKey) {
        return chunkZ(regionKey);
    }

    /**
     * Where in a region's 1024 entries a chunk sits.
     *
     * <p>{@code (x << 5) | z} — the mod's own index, not a choice. Swapping the two halves draws every
     * claim mirrored across the region diagonal, which looks like a plausible map right up until
     * somebody stands in one.
     */
    public static int indexInRegion(long chunkKey) {
        int x = chunkX(chunkKey) & 31;
        int z = chunkZ(chunkKey) & 31;
        return x << 5 | z;
    }

    /** The chunk a block column is in. */
    public static long ofBlock(int blockX, int blockZ) {
        return chunk(blockX >> 4, blockZ >> 4);
    }
}
