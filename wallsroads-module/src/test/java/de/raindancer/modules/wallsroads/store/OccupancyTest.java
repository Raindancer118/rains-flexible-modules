package de.raindancer.modules.wallsroads.store;

import de.raindancer.core.world.build.BatchBuilder;
import de.raindancer.core.world.build.BuildSnapshot;
import de.raindancer.core.world.safety.Spot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which structure owns which block — the answer that stops a second road paving over the first and
 * then, on being torn up, restoring a hole through it.
 */
class OccupancyTest {

    private static final Spot CROSSING = new Spot("world", 10, 70, 0);

    private final Occupancy occupancy = new Occupancy();

    private static BuildSnapshot snapshotOf(Spot... spots) {
        return new BuildSnapshot(java.util.Arrays.stream(spots)
                .map(spot -> new BuildSnapshot.Placement(spot, "GRASS_BLOCK")).toList());
    }

    @Test
    @DisplayName("nothing is owned until something claims it")
    void startsEmpty() {
        assertThat(occupancy.ownerOf(CROSSING)).isEmpty();
        assertThat(occupancy.isFreeFor(CROSSING, "road-1")).isTrue();
    }

    @Test
    @DisplayName("what a structure built is its own")
    void claimsWhatWasBuilt() {
        occupancy.claim("road-1", snapshotOf(CROSSING));

        assertThat(occupancy.ownerOf(CROSSING)).contains("road-1");
        assertThat(occupancy.isFreeFor(CROSSING, "road-2")).isFalse();
    }

    @Test
    @DisplayName("a structure may build over its own blocks — that is a rebuild, not a collision")
    void ownBlocksAreNotACollision() {
        occupancy.claim("road-1", snapshotOf(CROSSING));

        assertThat(occupancy.isFreeFor(CROSSING, "road-1")).isTrue();
    }

    @Test
    @DisplayName("tearing a structure down releases everything it held")
    void releasesOnTeardown() {
        occupancy.claim("road-1", snapshotOf(CROSSING, new Spot("world", 11, 70, 0)));

        occupancy.release("road-1");

        assertThat(occupancy.ownerOf(CROSSING)).isEmpty();
        assertThat(occupancy.count()).isZero();
    }

    @Test
    @DisplayName("a claim replaces that structure's previous one rather than adding to it")
    void claimingAgainReplaces() {
        occupancy.claim("road-1", snapshotOf(CROSSING));

        occupancy.claim("road-1", snapshotOf(new Spot("world", 50, 70, 0)));

        assertThat(occupancy.ownerOf(CROSSING)).isEmpty();
        assertThat(occupancy.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("placements onto somebody else's blocks are dropped, the rest are kept")
    void filtersOutWhatIsNotOurs() {
        occupancy.claim("road-1", snapshotOf(CROSSING));
        List<BatchBuilder.Placement> wanted = List.of(
                new BatchBuilder.Placement(CROSSING, "STONE"),
                new BatchBuilder.Placement(new Spot("world", 11, 70, 0), "STONE"));

        List<BatchBuilder.Placement> allowed = occupancy.filter(wanted, "road-2");

        assertThat(allowed).extracting(BatchBuilder.Placement::spot)
                .containsExactly(new Spot("world", 11, 70, 0));
    }

    @Test
    @DisplayName("two worlds do not share a crossing at the same coordinates")
    void keepsWorldsApart() {
        occupancy.claim("road-1", snapshotOf(CROSSING));

        assertThat(occupancy.isFreeFor(new Spot("nether", 10, 70, 0), "road-2")).isTrue();
    }
}
