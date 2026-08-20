package de.raindancer.modules.xaeromap.claims;

import de.raindancer.modules.claims.ClaimServices;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimNames;
import de.raindancer.modules.claims.model.ClaimShape;
import de.raindancer.modules.claims.store.ClaimRegistry;
import de.raindancer.modules.xaeromap.model.ClaimFacts;
import de.raindancer.modules.xaeromap.util.ChunkKeys;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That a claim's polygon becomes the right chunks.
 *
 * <p>The one genuinely arithmetic step between a claim on this server and a claim on somebody's
 * minimap. Wrong, it draws a border in the wrong place — which is worse than drawing none, because
 * somebody builds up to it.
 */
class ClaimsModuleSourceTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID WORLD = UUID.randomUUID();

    private ClaimRegistry registry;
    private ClaimServices services;
    private Server server;

    @BeforeEach
    void setUp() {
        registry = new ClaimRegistry();
        World world = Mockito.mock(World.class);
        Mockito.when(world.getKey()).thenReturn(NamespacedKey.minecraft("overworld"));
        server = Mockito.mock(Server.class);
        Mockito.when(server.getWorld(WORLD)).thenReturn(world);
        services = Mockito.mock(ClaimServices.class);
        Mockito.when(services.claims()).thenReturn(registry);
        Mockito.when(services.names()).thenReturn(Mockito.mock(ClaimNames.class));
    }

    private Claim claimOf(String name, ClaimShape shape) {
        Claim claim = new Claim(UUID.randomUUID(), name, WORLD, "world", shape, OWNER);
        registry.add(claim);
        return claim;
    }

    private ClaimFacts onlyFact() {
        List<ClaimFacts> facts = new ClaimsModuleSource(services, server).claims();
        assertThat(facts).hasSize(1);
        return facts.get(0);
    }

    @Test
    @DisplayName("a claim of exactly one chunk covers exactly that chunk, fully")
    void oneChunkIsOneChunk() {
        claimOf("Home", ClaimShape.rectangle(0, 0, 15, 15, 0, 255));

        Map<Long, Integer> coverage = onlyFact().chunkCoverage();

        assertThat(coverage).containsExactly(Map.entry(ChunkKeys.chunk(0, 0), 256));
    }

    @Test
    @DisplayName("a rectangle across a chunk boundary covers each side by exactly its own columns")
    void rectanglesAreExact() {
        // Eight columns wide, straddling x = 16: four columns in chunk 0, four in chunk 1, sixteen
        // columns deep in both.
        claimOf("Border", ClaimShape.rectangle(12, 0, 19, 15, 0, 255));

        Map<Long, Integer> coverage = onlyFact().chunkCoverage();

        assertThat(coverage).hasSize(2);
        assertThat(coverage.get(ChunkKeys.chunk(0, 0))).isEqualTo(4 * 16);
        assertThat(coverage.get(ChunkKeys.chunk(1, 0))).isEqualTo(4 * 16);
    }

    @Test
    @DisplayName("a claim west and north of spawn lands in the chunks west and north of spawn")
    void negativeCoordinatesWork() {
        claimOf("Westward", ClaimShape.rectangle(-16, -16, -1, -1, 0, 255));

        assertThat(onlyFact().chunkCoverage())
                .as("an integer division instead of a shift puts everything west of spawn one "
                        + "chunk too far east")
                .containsExactly(Map.entry(ChunkKeys.chunk(-1, -1), 256));
    }

    @Test
    @DisplayName("a polygon covers the chunks it actually reaches and no others")
    void polygonsAreSampled() {
        // An L, two chunks along the bottom and one going up. The chunk diagonally opposite the
        // corner is not part of it and must not be drawn.
        ClaimShape shape = new ClaimShape(List.of(
                new de.raindancer.modules.claims.model.ClaimPoint(0, 0),
                new de.raindancer.modules.claims.model.ClaimPoint(31, 0),
                new de.raindancer.modules.claims.model.ClaimPoint(31, 15),
                new de.raindancer.modules.claims.model.ClaimPoint(15, 15),
                new de.raindancer.modules.claims.model.ClaimPoint(15, 31),
                new de.raindancer.modules.claims.model.ClaimPoint(0, 31)), 0, 255);
        claimOf("Bent", shape);

        Map<Long, Integer> coverage = onlyFact().chunkCoverage();

        assertThat(coverage).containsKeys(ChunkKeys.chunk(0, 0), ChunkKeys.chunk(1, 0),
                ChunkKeys.chunk(0, 1));
        assertThat(coverage)
                .as("the missing arm of the L is ground nobody has claimed")
                .doesNotContainKey(ChunkKeys.chunk(1, 1));
    }

    @Test
    @DisplayName("a claim narrower than the sampling grid is still drawn")
    void thinClaimsAreNotLost() {
        // A two-block corridor can slip between sampled columns entirely, and a corridor is a real
        // shape somebody draws — a path, a wall, a bridge between two builds.
        claimOf("Corridor", ClaimShape.rectangle(0, 0, 1, 40, 0, 255));

        assertThat(onlyFact().chunkCoverage())
                .as("a claim that vanishes from the map because it is thin is a claim somebody "
                        + "builds over")
                .containsKeys(ChunkKeys.chunk(0, 0), ChunkKeys.chunk(0, 1), ChunkKeys.chunk(0, 2));
    }

    @Test
    @DisplayName("a claim in a world that is not loaded is left out rather than drawn nowhere")
    void unloadedWorldsAreSkipped() {
        Mockito.when(server.getWorld(WORLD)).thenReturn(null);
        claimOf("Elsewhere", ClaimShape.rectangle(0, 0, 15, 15, 0, 255));

        assertThat(new ClaimsModuleSource(services, server).claims())
                .as("the client has no such level either — and the claim comes back on the next "
                        + "refresh after the world does")
                .isEmpty();
    }

    @Test
    @DisplayName("the world travels as the key the client knows it by")
    void theDimensionIsTheWorldsKey() {
        claimOf("Home", ClaimShape.rectangle(0, 0, 15, 15, 0, 255));

        assertThat(onlyFact().dimensionKey()).isEqualTo("minecraft:overworld");
    }

    @Test
    @DisplayName("a resized claim is measured again rather than answered from the cache")
    void resizingInvalidatesTheCache() {
        Claim claim = claimOf("Home", ClaimShape.rectangle(0, 0, 15, 15, 0, 255));
        ClaimsModuleSource source = new ClaimsModuleSource(services, server);
        assertThat(source.claims().get(0).chunkCoverage()).hasSize(1);

        claim.shape(ClaimShape.rectangle(0, 0, 47, 15, 0, 255));

        assertThat(source.claims().get(0).chunkCoverage())
                .as("the cache is keyed on the shape precisely so a resize is not answered with "
                        + "the old footprint")
                .hasSize(3);
    }

    @Test
    @DisplayName("a claim that is deleted stops being cached")
    void thecacheDoesNotGrowForever() {
        Claim claim = claimOf("Home", ClaimShape.rectangle(0, 0, 15, 15, 0, 255));
        ClaimsModuleSource source = new ClaimsModuleSource(services, server);
        source.claims();

        registry.remove(claim);

        assertThat(source.claims()).isEmpty();
        // Nothing observable is left to assert on beyond this; the retainAll in claims() is what
        // keeps a server that makes and deletes claims all day from growing an entry per claim ever
        // made.
        assertThat(source.available()).isTrue();
    }
}
