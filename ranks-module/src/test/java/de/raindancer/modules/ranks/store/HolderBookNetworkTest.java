package de.raindancer.modules.ranks.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.store.Documents;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.testkit.TestDatabases;
import de.raindancer.modules.ranks.model.Holder;
import de.raindancer.modules.ranks.model.Place;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A player's rank is the same on every server of the network. */
@Tag("database")
class HolderBookNetworkTest {

    private static final UUID ADA = UUID.randomUUID();
    private static final UUID BO = UUID.randomUUID();

    @TempDir
    Path plugins;

    private Servers network;

    private HolderBook on(String id) {
        if (network == null) {
            network = new Servers(plugins);
        }
        HolderBook book = new HolderBook(network.network(network.server(id), "holders.yml"));
        book.load();
        book.watch();
        return book;
    }

    @AfterEach
    void close() {
        if (network != null) {
            network.close();
        }
    }

    @Test
    @DisplayName("a rank given on one server is held on the other")
    void rankFollowsThePlayer() {
        HolderBook smp = on("smp");
        HolderBook arena = on("arena");

        assertThat(smp.change(ADA, holder -> holder.withRanks(Set.of("owner")))).isTrue();
        network.heard();

        assertThat(arena.of(ADA).ranks()).containsExactly("owner");
    }

    @Test
    @DisplayName("taking every rank away on one server takes it away on the other")
    void removalTravels() {
        HolderBook smp = on("smp");
        HolderBook arena = on("arena");
        smp.change(ADA, holder -> holder.withRanks(Set.of("owner")));
        network.heard();

        arena.change(ADA, holder -> holder.withRanks(Set.of()));
        network.heard();

        assertThat(smp.find(ADA)).isEmpty();
    }

    @Test
    @DisplayName("two players changed at once on two servers both keep their change")
    void bothKept() {
        HolderBook smp = on("smp");
        HolderBook arena = on("arena");

        smp.change(ADA, holder -> holder.withRanks(Set.of("owner")));
        arena.change(BO, holder -> holder.withRanks(Set.of("dev")));
        network.heard();

        assertThat(smp.of(BO).ranks()).containsExactly("dev");
        assertThat(arena.of(ADA).ranks()).containsExactly("owner");
    }

    @Test
    @DisplayName("a change is made to the player as the document has them now, not as this server last saw them")
    void changeIsAppliedToWhatIsStored() {
        HolderBook smp = on("smp");
        HolderBook arena = on("arena");

        smp.change(ADA, holder -> holder.withRanks(Set.of("owner")));
        // The arena has not heard yet.
        arena.change(ADA, holder -> holder.showing(Place.CHAT, false));
        network.heard();

        Holder onSmp = smp.of(ADA);
        assertThat(onSmp.ranks()).containsExactly("owner");
        assertThat(onSmp.shows(Place.CHAT)).isFalse();
        assertThat(arena.of(ADA)).isEqualTo(onSmp);
    }

    @Test
    @DisplayName("a document that cannot be read leaves what is held alone and refuses every change")
    void unreadableIsNotWrittenOver() throws Exception {
        HolderBook healthy = on("smp");
        healthy.change(ADA, holder -> holder.withRanks(Set.of("owner")));
        Database broken = TestDatabases.unusable(plugins.resolve("broken.db"), Documents.SCHEMA);
        try {
            Documents documents = new Documents(broken, null, plugins, "arena", System::currentTimeMillis);
            HolderBook book = new HolderBook(new YamlStore(plugins.resolve("RainsRanks").resolve("holders.yml"),
                    documents, Documents.Scope.NETWORK));
            book.load();

            assertThat(book.readable()).isFalse();
            assertThat(book.change(BO, holder -> holder.withRanks(Set.of("dev")))).isFalse();
            assertThat(book.find(BO)).isEmpty();
        } finally {
            broken.close();
        }
        assertThat(healthy.of(ADA).ranks()).containsExactly("owner");
    }

    @Test
    @DisplayName("a read that fails after a good one keeps the ranks held")
    void failedReloadKeepsWhatIsHeld() {
        Database database = TestDatabases.open(plugins.resolve("lost.db"), Documents.SCHEMA);
        Documents documents = new Documents(database, null, plugins, "smp", System::currentTimeMillis);
        HolderBook book = new HolderBook(new YamlStore(plugins.resolve("RainsRanks").resolve("holders.yml"),
                documents, Documents.Scope.NETWORK));
        book.load();
        book.change(ADA, holder -> holder.withRanks(Set.of("owner")));

        database.close();
        book.load();

        assertThat(book.readable()).isFalse();
        assertThat(book.of(ADA).ranks()).containsExactly("owner");
    }
}
