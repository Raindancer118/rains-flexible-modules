package de.raindancer.modules.warp.store;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.world.poi.PoiStore;
import de.raindancer.modules.warp.model.Warp;
import de.raindancer.modules.warp.model.WarpAccess;
import de.raindancer.modules.warp.rules.WarpAccessRule;
import de.raindancer.modules.warp.util.PermissionNodes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/** Owners and their people, kept on the warp itself — so they survive a restart and a move. */
class WarpCatalogueOwnershipTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID FRIEND = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final Predicate<String> PLAYER = Set.of(PermissionNodes.USE)::contains;

    @TempDir
    Path directory;

    private Database database;
    private PoiStore places;
    private WarpCatalogue catalogue;
    private final WarpAccessRule rule = new WarpAccessRule();

    @BeforeEach
    void setUp() {
        database = Database.open(directory.resolve("core.db"), CoreSchema.CORE, () -> false);
        places = new PoiStore(database);
        catalogue = catalogueOver(places);
    }

    @AfterEach
    void close() {
        database.close();
    }

    private static WarpCatalogue catalogueOver(PoiStore places) {
        // The store's own flush, as the module hands it in: what is not flushed is not on disk.
        return new WarpCatalogue(new WarpRegistry(places, () -> 0L, world -> true), places::flush);
    }

    private Warp made(String name, UUID by) {
        return catalogue.create(name, "world", 1, 64, 1, by).orElseThrow();
    }

    @Test
    @DisplayName("whoever sets a warp owns it, and staff can hand it to somebody else")
    void owning() {
        made("cave", OWNER);
        assertThat(catalogue.byName("cave").orElseThrow().owner()).contains(OWNER);
        assertThat(catalogue.ownedBy(OWNER)).extracting(Warp::name).containsExactly("cave");

        assertThat(catalogue.setOwner("cave", FRIEND)).isTrue();

        assertThat(catalogue.byName("cave").orElseThrow().owner()).contains(FRIEND);
        assertThat(catalogue.ownedBy(OWNER)).isEmpty();
    }

    @Test
    @DisplayName("people added to a warp are kept with it, through a move and a restart")
    void members() {
        made("cave", OWNER);
        catalogue.setAccess("cave", WarpAccess.PRIVATE);
        assertThat(catalogue.addMember("cave", FRIEND)).isTrue();
        catalogue.move("cave", "world", 10, 70, 10, 0, 0);

        PoiStore reopened = new PoiStore(database);
        reopened.load();
        Warp after = catalogueOver(reopened).byName("cave").orElseThrow();

        assertThat(after.members()).containsExactly(FRIEND);
        assertThat(after.owner()).contains(OWNER);
        assertThat(catalogue.accessOf(after)).isEqualTo(WarpAccess.PRIVATE);

        assertThat(catalogue.removeMember("cave", FRIEND)).isTrue();
        assertThat(catalogue.byName("cave").orElseThrow().members()).isEmpty();
    }

    @Test
    @DisplayName("a private warp is on its owner's and their people's lists, and on nobody else's")
    void listing() {
        made("cave", OWNER);
        made("spawn", STRANGER);
        catalogue.setAccess("cave", WarpAccess.PRIVATE);
        catalogue.addMember("cave", FRIEND);

        assertThat(catalogue.visibleTo(OWNER, PLAYER, rule)).extracting(Warp::name).contains("cave", "spawn");
        assertThat(catalogue.visibleTo(FRIEND, PLAYER, rule)).extracting(Warp::name).contains("cave");
        assertThat(catalogue.visibleTo(STRANGER, PLAYER, rule)).extracting(Warp::name)
                .containsExactly("spawn");
    }
}
