package de.raindancer.modules.claims;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.world.poi.ClaimWarps;
import de.raindancer.core.world.poi.PoiStore;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimShape;
import de.raindancer.modules.claims.service.ClaimWarpService;
import de.raindancer.modules.claims.service.ClaimWarpService.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A claim's front door as a warp, and a player's main claim as their home — kept in step with the claim
 * through renames, hand-overs and deletion, because a warp that outlives its claim sends people to
 * somebody else's land.
 */
class ClaimWarpServiceTest {

    private static final UUID WORLD = UUID.randomUUID();
    private static final UUID LILLY = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BEN = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    @TempDir
    Path directory;

    private Database database;
    private ClaimWarps warps;
    private ClaimWarpService service;
    private Claim farm;

    @BeforeEach
    void setUp() {
        database = Database.open(directory.resolve("core.db"), CoreSchema.CORE, () -> false);
        warps = new ClaimWarps(new PoiStore(database));
        service = new ClaimWarpService(warps);
        farm = new Claim(UUID.randomUUID(), "Farm", WORLD, "world",
                ClaimShape.rectangle(0, 0, 20, 20, 0, 128), LILLY);
    }

    @AfterEach
    void close() {
        database.close();
    }

    private String id(Claim claim) {
        return claim.id().toString();
    }

    @Test
    @DisplayName("its owner sets the warp where they stand, inside the claim; it is the claim's front door too")
    void setting() {
        assertThat(service.set(LILLY, false, farm, "world", 5.5, 64, 6.5, 90, 0)).isEqualTo(Outcome.SET);

        assertThat(warps.forClaim(id(farm))).isPresent();
        assertThat(farm.entrance()).isPresent();
        assertThat(farm.entrance().orElseThrow().x()).isEqualTo(5);
    }

    @Test
    @DisplayName("not outside the claim, and not by somebody who does not own it — staff may")
    void refusals() {
        assertThat(service.set(LILLY, false, farm, "world", 50, 64, 50, 0, 0)).isEqualTo(Outcome.OUTSIDE);
        assertThat(service.set(STRANGER, false, farm, "world", 5, 64, 5, 0, 0)).isEqualTo(Outcome.NOT_ALLOWED);
        assertThat(warps.forClaim(id(farm))).isEmpty();

        assertThat(service.set(STRANGER, true, farm, "world", 5, 64, 5, 0, 0)).isEqualTo(Outcome.SET);
    }

    @Test
    @DisplayName("not from another world at the same coordinates, nor above or below the claim's height — "
            + "a point outside the claim is not covered by its teleport-in rules")
    void onlyInsideTheClaimItself() {
        assertThat(service.set(LILLY, false, farm, "world_nether", 5, 64, 5, 0, 0)).isEqualTo(Outcome.OUTSIDE);
        assertThat(service.set(LILLY, false, farm, "world", 5, 200, 5, 0, 0)).isEqualTo(Outcome.OUTSIDE);
        assertThat(service.set(LILLY, false, farm, "world", 5, -10, 5, 0, 0)).isEqualTo(Outcome.OUTSIDE);
        assertThat(warps.forClaim(id(farm))).isEmpty();
        assertThat(farm.entrance()).as("the front door is not moved either").isEmpty();
    }

    @Test
    @DisplayName("a claim made smaller or shallower than its warp loses the warp, rather than keeping one outside it")
    void reshaping() {
        service.set(LILLY, false, farm, "world", 15, 64, 15, 0, 0);

        farm.shape(ClaimShape.rectangle(0, 0, 10, 10, 0, 128));
        service.reshaped(farm);
        assertThat(warps.forClaim(id(farm))).isEmpty();
        assertThat(farm.entrance()).isEmpty();

        service.set(LILLY, false, farm, "world", 5, 64, 5, 0, 0);
        farm.shape(ClaimShape.rectangle(0, 0, 20, 20, 0, 128));
        service.reshaped(farm);
        assertThat(warps.forClaim(id(farm))).as("still inside: kept").isPresent();
    }

    @Test
    @DisplayName("an owner makes it their main home — once it has a warp; a stranger cannot make it theirs")
    void home() {
        assertThat(service.makeHome(LILLY, farm)).isEqualTo(Outcome.NO_WARP);
        service.set(LILLY, false, farm, "world", 5, 64, 5, 0, 0);

        assertThat(service.makeHome(STRANGER, farm)).isEqualTo(Outcome.NOT_ALLOWED);
        assertThat(service.makeHome(LILLY, farm)).isEqualTo(Outcome.HOME);
        assertThat(warps.mainOf(LILLY).map(ClaimWarps::claimOf)).contains(id(farm));
    }

    @Test
    @DisplayName("a co-owner may call it home too, and stops being able to once they are no longer an owner")
    void coOwner() {
        farm.addOwner(BEN);
        service.set(LILLY, false, farm, "world", 5, 64, 5, 0, 0);
        assertThat(service.makeHome(BEN, farm)).isEqualTo(Outcome.HOME);

        farm.removeOwner(BEN);
        service.ownerRemoved(farm, BEN);

        assertThat(warps.mainOf(BEN)).isEmpty();
    }

    @Test
    @DisplayName("renamed, handed over or deleted, the warp follows the claim")
    void followsTheClaim() {
        service.set(LILLY, false, farm, "world", 5, 64, 5, 0, 0);
        service.makeHome(LILLY, farm);

        farm.name("Big Farm");
        service.renamed(farm);
        assertThat(warps.forClaim(id(farm)).orElseThrow().name()).isEqualTo("Big Farm");

        farm.transferTo(BEN);
        service.transferred(farm);
        assertThat(warps.forClaim(id(farm)).orElseThrow().owner()).isEqualTo(BEN);
        assertThat(warps.mainOf(LILLY)).as("not hers any more").isEmpty();

        service.deleted(farm);
        assertThat(warps.forClaim(id(farm))).isEmpty();
    }

    @Test
    @DisplayName("removing the warp clears the front door, and with it anybody's home there")
    void removing() {
        service.set(LILLY, false, farm, "world", 5, 64, 5, 0, 0);
        service.makeHome(LILLY, farm);

        assertThat(service.clear(LILLY, false, farm)).isEqualTo(Outcome.CLEARED);

        assertThat(farm.entrance()).isEmpty();
        assertThat(warps.mainOf(LILLY)).isEmpty();
    }

    @Test
    @DisplayName("at start, a front door set before this existed becomes a warp, and a warp whose claim is gone goes")
    void catchingUp() {
        farm.entrance(new de.raindancer.modules.claims.model.ClaimPoint(3, 4), 70);
        warps.set("gone-claim", "Ruin", BEN, "world", 1, 64, 1, 0, 0);

        service.catchUp(List.of(farm), world -> "world");

        assertThat(warps.forClaim(id(farm))).isPresent();
        assertThat(warps.forClaim(id(farm)).orElseThrow().y()).isEqualTo(70);
        assertThat(warps.forClaim("gone-claim")).isEmpty();
    }
}
