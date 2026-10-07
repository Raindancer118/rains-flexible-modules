package de.raindancer.modules.warp;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.world.poi.ClaimWarps;
import de.raindancer.core.world.poi.Poi;
import de.raindancer.core.world.poi.PoiStore;
import de.raindancer.modules.warp.service.ClaimWarpDirectory;
import de.raindancer.modules.warp.service.ClaimWarpDirectory.Found;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Finding a claim's warp by what somebody types — the claim's name, or {@code owner/claim} where two
 * claims share one — and a player's main home by their name. Claims they would be turned away at are
 * not offered at all.
 */
class ClaimWarpDirectoryTest {

    private static final UUID LILLY = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BEN = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final Map<UUID, String> NAMES = Map.of(LILLY, "Lilly", BEN, "Ben");
    private static final Predicate<Poi> ANYWHERE = point -> true;

    @TempDir
    Path directory;

    private Database database;
    private ClaimWarps warps;
    private ClaimWarpDirectory claims;

    @BeforeEach
    void setUp() {
        database = Database.open(directory.resolve("core.db"), CoreSchema.CORE, () -> false);
        warps = new ClaimWarps(new PoiStore(database));
        claims = new ClaimWarpDirectory(warps, NAMES::get);
        warps.set("c1", "Farm", LILLY, "world", 1, 64, 1, 0, 0);
        warps.set("c2", "Farm", BEN, "world", 2, 64, 2, 0, 0);
        warps.set("c3", "Tower", LILLY, "world", 3, 64, 3, 0, 0);
    }

    @AfterEach
    void close() {
        database.close();
    }

    @Test
    @DisplayName("a claim's name finds it; one two claims share is ambiguous until the owner is named")
    void byName() {
        assertThat(claims.find("tower", ANYWHERE)).isInstanceOf(Found.One.class);
        assertThat(claims.find("farm", ANYWHERE)).isInstanceOf(Found.Several.class);
        assertThat(((Found.One) claims.find("ben/farm", ANYWHERE)).point().owner()).isEqualTo(BEN);
        assertThat(claims.find("nowhere", ANYWHERE)).isInstanceOf(Found.None.class);
    }

    @Test
    @DisplayName("a claim somebody would be turned away at is not found for them, and not listed")
    void hidden() {
        Predicate<Poi> notBens = point -> !BEN.equals(point.owner());

        assertThat(claims.find("farm", notBens)).as("only Lilly's is left").isInstanceOf(Found.One.class);
        assertThat(claims.visible(notBens)).extracting(Poi::owner).containsOnly(LILLY);
    }

    @Test
    @DisplayName("what a list shows and completion offers: the bare name where it is unique, owner/name where not")
    void typedAs() {
        assertThat(claims.typedAs(warps.forClaim("c3").orElseThrow(), ANYWHERE)).isEqualTo("tower");
        assertThat(claims.typedAs(warps.forClaim("c1").orElseThrow(), ANYWHERE)).isEqualTo("lilly/farm");
    }

    @Test
    @DisplayName("a player's main home is found by their name — when they have one, and the claim lets you in")
    void home() {
        warps.markMain(LILLY, "c3");

        assertThat(claims.homeOf(LILLY, ANYWHERE).map(Poi::name)).contains("Tower");
        assertThat(claims.homeOf(BEN, ANYWHERE)).isEmpty();
        assertThat(claims.homeOf(LILLY, point -> false)).isEmpty();
    }
}
