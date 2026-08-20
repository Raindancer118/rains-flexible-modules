package de.raindancer.modules.xaeromap;

import de.raindancer.modules.xaeromap.model.ClaimFacts;
import de.raindancer.modules.xaeromap.util.ChunkKeys;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Claims to test with, without a server or a claims plugin anywhere near it.
 *
 * <p>Every claim built here is a whole chunk unless it is told otherwise, because "a claim covering
 * exactly these chunks" is what nearly every test in this module is actually about.
 */
public final class Facts {

    public static final String OVERWORLD = "minecraft:overworld";
    public static final String NETHER = "minecraft:the_nether";

    private Facts() {
    }

    /** A claim of whole chunks, owned by nobody in particular. */
    public static ClaimFacts claim(String name, UUID owner, String dimension, long... chunks) {
        return claim(name, owner, dimension, 0L, Set.of(), chunks);
    }

    public static ClaimFacts claim(String name, UUID owner, String dimension, long createdAt,
                                   Set<UUID> members, long... chunks) {
        Map<Long, Integer> coverage = new HashMap<>();
        for (long chunk : chunks) {
            coverage.put(chunk, 256);
        }
        return new ClaimFacts(UUID.randomUUID(), name, owner, owner.toString().substring(0, 8),
                members, UUID.nameUUIDFromBytes(dimension.getBytes()), dimension, createdAt, coverage);
    }

    /** A claim covering part of one chunk. */
    public static ClaimFacts partial(String name, UUID owner, long chunk, int columns) {
        return partial(name, owner, chunk, columns, 0L);
    }

    public static ClaimFacts partial(String name, UUID owner, long chunk, int columns, long createdAt) {
        return new ClaimFacts(UUID.randomUUID(), name, owner, "someone", Set.of(),
                UUID.nameUUIDFromBytes(OVERWORLD.getBytes()), OVERWORLD, createdAt,
                Map.of(chunk, columns));
    }

    public static long chunk(int x, int z) {
        return ChunkKeys.chunk(x, z);
    }
}
