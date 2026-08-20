package de.raindancer.modules.xaeromap.claims;

import de.raindancer.modules.claims.ClaimServices;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimShape;
import de.raindancer.modules.xaeromap.model.ClaimFacts;
import de.raindancer.modules.xaeromap.util.ChunkKeys;
import org.bukkit.Server;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * claims-module's claims, as chunks.
 *
 * <h2>The one real piece of arithmetic in this module</h2>
 * A claim is a polygon of block columns; a map draws chunks. So every claim has to be turned into "how
 * much of each chunk is this", and there are two cases:
 *
 * <ul>
 *   <li><b>A rectangle</b> — which nearly every claim is — is exact and cheap: the overlap of two
 *       rectangles is arithmetic, no per-column test at all.</li>
 *   <li><b>Anything else</b> is sampled on a four-by-four grid inside each chunk, and the count scaled
 *       back up. Sixteen tests per chunk instead of two hundred and fifty-six, and the answer only ever
 *       feeds a percentage threshold that decides whether to paint the chunk at all — a claim covering
 *       a tenth of a chunk and one covering an eighth are drawn identically, so precision past this
 *       point buys nothing and costs sixteen times as much.</li>
 * </ul>
 *
 * <p>Either way the shape's own {@code containsColumn} does the deciding, never a second copy of it
 * here: claims-module's version knows about edge columns and diagonal slack, and a reimplementation
 * would disagree with the claim's own idea of where it is — which is a border drawn in the wrong place.
 *
 * <p><b>Cached by shape.</b> The result is remembered per claim until its shape or world changes, so the
 * refresh timer walking every claim on the server costs a map lookup each rather than the arithmetic
 * again. A resize is what invalidates one, which is exactly when the answer is different.
 */
public final class ClaimsModuleSource implements ClaimSource {

    /** Blocks between sampled columns in a non-rectangular claim: a 4 × 4 grid per chunk. */
    private static final int SAMPLE_STEP = 4;
    private static final int SAMPLES_PER_CHUNK = 16 * 16 / (SAMPLE_STEP * SAMPLE_STEP);

    private record Cached(int shape, String dimension, Map<Long, Integer> coverage) {
    }

    private final ClaimServices claims;
    private final Server server;
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

    public ClaimsModuleSource(ClaimServices claims, Server server) {
        this.claims = claims;
        this.server = server;
    }

    @Override
    public String name() {
        return "claims-module (" + claims.claims().size() + " claim(s))";
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public List<ClaimFacts> claims() {
        List<ClaimFacts> facts = new ArrayList<>();
        for (Claim claim : claims.claims().all()) {
            String dimension = dimensionOf(claim.worldId());
            if (dimension == null) {
                // A claim in a world that is not loaded right now. The client has no such level either,
                // so there is nowhere to draw it — and it comes back on the next refresh after the world
                // does, rather than being forgotten.
                continue;
            }
            facts.add(new ClaimFacts(claim.id(), claim.name(), claim.primaryOwner(),
                    claims.names().nameOfOwner(claim.primaryOwner()), claim.members().keySet(),
                    claim.worldId(), dimension, claim.createdAt(),
                    coverageOf(claim, dimension)));
        }
        cache.keySet().retainAll(claims.claims().snapshot().keySet());
        return facts;
    }

    /** The client's own name for the level a world is: {@code minecraft:the_nether} and the like. */
    private String dimensionOf(UUID worldId) {
        if (server == null) {
            return null;
        }
        World world = server.getWorld(worldId);
        return world == null ? null : world.getKey().toString();
    }

    private Map<Long, Integer> coverageOf(Claim claim, String dimension) {
        ClaimShape shape = claim.shape();
        int signature = shape.hashCode();
        Cached cached = cache.get(claim.id());
        if (cached != null && cached.shape() == signature && cached.dimension().equals(dimension)) {
            return cached.coverage();
        }
        Map<Long, Integer> coverage = shape.isRectangle() ? exactly(shape) : sampled(shape);
        cache.put(claim.id(), new Cached(signature, dimension, coverage));
        return coverage;
    }

    /** Rectangle against chunk grid: the overlap in each direction, multiplied. */
    private static Map<Long, Integer> exactly(ClaimShape shape) {
        Map<Long, Integer> coverage = new HashMap<>();
        for (int chunkX = shape.minX() >> 4; chunkX <= (shape.maxX() >> 4); chunkX++) {
            int fromX = Math.max(shape.minX(), chunkX << 4);
            int toX = Math.min(shape.maxX(), (chunkX << 4) + 15);
            for (int chunkZ = shape.minZ() >> 4; chunkZ <= (shape.maxZ() >> 4); chunkZ++) {
                int fromZ = Math.max(shape.minZ(), chunkZ << 4);
                int toZ = Math.min(shape.maxZ(), (chunkZ << 4) + 15);
                int columns = (toX - fromX + 1) * (toZ - fromZ + 1);
                if (columns > 0) {
                    coverage.put(ChunkKeys.chunk(chunkX, chunkZ), columns);
                }
            }
        }
        return Map.copyOf(coverage);
    }

    /** Everything else: a grid of sampled columns per chunk, scaled back up to a column count. */
    private static Map<Long, Integer> sampled(ClaimShape shape) {
        Map<Long, Integer> coverage = new HashMap<>();
        for (int chunkX = shape.minX() >> 4; chunkX <= (shape.maxX() >> 4); chunkX++) {
            for (int chunkZ = shape.minZ() >> 4; chunkZ <= (shape.maxZ() >> 4); chunkZ++) {
                int hits = 0;
                for (int offsetX = 1; offsetX < 16; offsetX += SAMPLE_STEP) {
                    for (int offsetZ = 1; offsetZ < 16; offsetZ += SAMPLE_STEP) {
                        if (shape.containsColumn((chunkX << 4) + offsetX, (chunkZ << 4) + offsetZ)) {
                            hits++;
                        }
                    }
                }
                if (hits == 0) {
                    // A claim narrower than the sampling step can slip between the sampled columns
                    // entirely — a two-block-wide corridor is a real shape somebody draws. The corners
                    // of where its bounding box meets this chunk cannot be missed the same way, so a hit
                    // there counts as one sample's worth rather than nothing.
                    hits = cornersInside(shape, chunkX, chunkZ) ? 1 : 0;
                }
                if (hits > 0) {
                    coverage.put(ChunkKeys.chunk(chunkX, chunkZ),
                            Math.min(256, hits * (256 / SAMPLES_PER_CHUNK)));
                }
            }
        }
        return Map.copyOf(coverage);
    }

    private static boolean cornersInside(ClaimShape shape, int chunkX, int chunkZ) {
        int fromX = Math.max(shape.minX(), chunkX << 4);
        int toX = Math.min(shape.maxX(), (chunkX << 4) + 15);
        int fromZ = Math.max(shape.minZ(), chunkZ << 4);
        int toZ = Math.min(shape.maxZ(), (chunkZ << 4) + 15);
        if (fromX > toX || fromZ > toZ) {
            return false;
        }
        return shape.containsColumn(fromX, fromZ) || shape.containsColumn(toX, fromZ)
                || shape.containsColumn(fromX, toZ) || shape.containsColumn(toX, toZ);
    }
}
