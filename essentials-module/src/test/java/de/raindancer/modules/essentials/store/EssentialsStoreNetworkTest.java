package de.raindancer.modules.essentials.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.store.Documents;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.testkit.TestDatabases;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** Nicknames and ignore lists are the player's, wherever they play: one document for the network. */
@Tag("database")
class EssentialsStoreNetworkTest {

    @TempDir
    Path plugins;

    private Servers servers;
    private final UUID tom = UUID.randomUUID();
    private final UUID bea = UUID.randomUUID();

    private EssentialsStore on(String id) {
        if (servers == null) {
            servers = new Servers(plugins);
        }
        EssentialsStore store = new EssentialsStore(servers.network(servers.server(id), "essentials.yml"));
        store.load();
        store.watch();
        return store;
    }

    @AfterEach
    void close() {
        if (servers != null) {
            servers.close();
        }
    }

    @Test
    @DisplayName("a nickname set on one server is worn on the other")
    void nicknameTravels() {
        EssentialsStore smp = on("smp");
        EssentialsStore arena = on("arena");

        smp.setNickname(tom, "<red>Tommy</red>");
        assertThat(smp.flush()).isTrue();
        servers.heard();

        assertThat(arena.nicknameOf(tom)).contains("<red>Tommy</red>");
    }

    @Test
    @DisplayName("ignoring somebody on one server holds on the other, and unblocking too")
    void ignoreTravels() {
        EssentialsStore smp = on("smp");
        EssentialsStore arena = on("arena");

        smp.ignore(tom, bea);
        smp.flush();
        servers.heard();
        assertThat(arena.isIgnoring(tom, bea)).isTrue();

        arena.stopIgnoring(tom, bea);
        arena.flush();
        servers.heard();
        assertThat(smp.isIgnoring(tom, bea)).isFalse();
    }

    @Test
    @DisplayName("changes made on two servers at once are both kept")
    void bothChangesKept() {
        EssentialsStore smp = on("smp");
        EssentialsStore arena = on("arena");

        smp.setNickname(tom, "Tommy");
        arena.ignore(bea, tom);
        smp.flush();
        arena.flush();
        servers.heard();

        assertThat(smp.isIgnoring(bea, tom)).isTrue();
        assertThat(arena.nicknameOf(tom)).contains("Tommy");
    }

    @Test
    @DisplayName("a change not yet written survives the reload another server's write causes")
    void unwrittenChangeSurvives() {
        EssentialsStore smp = on("smp");
        EssentialsStore arena = on("arena");

        smp.ignore(tom, bea);
        arena.setNickname(bea, "Bee");
        arena.flush();
        servers.heard();

        assertThat(smp.isIgnoring(tom, bea)).isTrue();
        assertThat(smp.nicknameOf(bea)).contains("Bee");
        smp.flush();
        servers.heard();
        assertThat(arena.isIgnoring(tom, bea)).isTrue();
    }

    @Test
    @DisplayName("a nickname somebody on the other server already has is taken, in any spelling")
    void nicknameIsUniqueAcrossServers() {
        EssentialsStore smp = on("smp");
        EssentialsStore arena = on("arena");
        assertThat(smp.claimNickname(tom, "Star Light")).isEqualTo(EssentialsStore.Claim.GRANTED);

        assertThat(arena.claimNickname(bea, "star_light")).isEqualTo(EssentialsStore.Claim.TAKEN);
        assertThat(arena.claimNickname(bea, "<gold>STAR LIGHT</gold>")).isEqualTo(EssentialsStore.Claim.TAKEN);
        assertThat(arena.nicknameOf(bea)).isEmpty();
        assertThat(arena.nicknameOf(tom)).contains("Star Light");
    }

    @Test
    @DisplayName("somebody may change the look of their own nickname, and a freed one can be claimed")
    void ownAndFreed() {
        EssentialsStore smp = on("smp");
        EssentialsStore arena = on("arena");
        assertThat(smp.claimNickname(tom, "Starlight")).isEqualTo(EssentialsStore.Claim.GRANTED);
        assertThat(smp.claimNickname(tom, "<red>Starlight</red>")).isEqualTo(EssentialsStore.Claim.GRANTED);

        smp.clearNickname(tom);
        smp.flush();
        servers.heard();

        assertThat(arena.claimNickname(bea, "Starlight")).isEqualTo(EssentialsStore.Claim.GRANTED);
    }

    @Test
    @DisplayName("two servers claiming the same nickname at the same moment: exactly one gets it")
    void raceHasOneWinner() throws Exception {
        EssentialsStore smp = on("smp");
        EssentialsStore arena = on("arena");
        CyclicBarrier together = new CyclicBarrier(2);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<EssentialsStore.Claim> first = threads.submit(() -> {
                together.await();
                return smp.claimNickname(tom, "Highlander");
            });
            Future<EssentialsStore.Claim> second = threads.submit(() -> {
                together.await();
                return arena.claimNickname(bea, "Highlander");
            });

            assertThat(java.util.List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(EssentialsStore.Claim.GRANTED, EssentialsStore.Claim.TAKEN);
        } finally {
            threads.shutdownNow();
        }
        servers.heard();
        boolean tomHas = smp.nicknameOf(tom).isPresent();
        assertThat(smp.nicknameOf(bea).isPresent()).isNotEqualTo(tomHas);
        assertThat(arena.nicknameOf(tom).isPresent()).isEqualTo(tomHas);
    }

    @Test
    @DisplayName("a nickname from before this version, with nothing else recorded, still blocks the same name")
    void oldNicknameBlocks() {
        servers = new Servers(plugins);
        Documents documents = servers.server("smp");
        servers.network(documents, "essentials.yml").write(yaml -> yaml.set("nicknames." + tom, "Old Timer"));
        EssentialsStore arena = on("arena");

        assertThat(arena.claimNickname(bea, "old_timer")).isEqualTo(EssentialsStore.Claim.TAKEN);
    }

    @Test
    @DisplayName("when the database cannot be reached nothing is granted, and nothing is said to be")
    void unavailableGrantsNothing() throws Exception {
        Database broken = TestDatabases.unusable(plugins.resolve("broken.db"), Documents.SCHEMA);
        try {
            Documents documents = new Documents(broken, null, plugins, "smp", System::currentTimeMillis);
            EssentialsStore store = new EssentialsStore(new YamlStore(
                    plugins.resolve("RainsEssentials").resolve("essentials.yml"), documents, Documents.Scope.NETWORK));
            store.load();

            assertThat(store.claimNickname(tom, "Anything")).isEqualTo(EssentialsStore.Claim.UNAVAILABLE);
            assertThat(store.nicknameOf(tom)).isEmpty();
        } finally {
            broken.close();
        }
    }

    @Test
    @DisplayName("a read that fails leaves what is held, and an ignore made meanwhile is still written later")
    void failedReadKeepsWhatIsHeld() throws Exception {
        Database broken = TestDatabases.unusable(plugins.resolve("broken.db"), Documents.SCHEMA);
        try {
            Documents documents = new Documents(broken, null, plugins, "smp", System::currentTimeMillis);
            EssentialsStore store = new EssentialsStore(new YamlStore(
                    plugins.resolve("RainsEssentials").resolve("essentials.yml"), documents, Documents.Scope.NETWORK));

            assertThat(store.load()).isFalse();
            store.ignore(tom, bea);

            assertThat(store.flush()).isFalse();
            assertThat(store.isIgnoring(tom, bea)).isTrue();
        } finally {
            broken.close();
        }
    }
}
