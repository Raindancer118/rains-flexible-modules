package de.raindancer.modules.invsnap.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.store.DocumentFolder;
import de.raindancer.core.data.store.Documents;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.testkit.TestDatabases;
import de.raindancer.modules.invsnap.model.ItemPolicy;
import de.raindancer.modules.invsnap.model.Snapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("database")
@DisplayName("the stores keep their data as documents in the database, not as files")
class StoresInTheDatabaseTest {

    @TempDir
    Path plugins;

    private Database database;
    private Documents documents;
    private Path data;

    @BeforeEach
    void open() {
        database = TestDatabases.open(plugins.resolve("documents.db"), Documents.SCHEMA);
        documents = new Documents(database, null, plugins, "smp", System::currentTimeMillis);
        data = plugins.resolve("RainsInvSnap");
    }

    @AfterEach
    void close() {
        database.close();
    }

    private SnapshotStore snapshots() {
        return new SnapshotStore(DocumentFolder.on(data.resolve("snapshots"), documents, Documents.Scope.SERVER));
    }

    private YamlStore yaml(String name) {
        return new YamlStore(data.resolve(name), documents, Documents.Scope.SERVER);
    }

    private static Snapshot taken(UUID player, Instant at) {
        return new Snapshot(player, "Someone", at, List.of("sword"), List.of(), null);
    }

    @Test
    @DisplayName("snapshots are listed and loaded from documents; no file is written")
    void snapshotsAreDocuments() {
        UUID player = UUID.randomUUID();
        Instant at = Instant.ofEpochMilli(1_700_000_000_000L);

        assertThat(snapshots().saveAll(player, List.of(taken(player, at)))).isTrue();

        assertThat(snapshots().knownPlayerIds()).containsExactly(player);
        assertThat(snapshots().load(player)).extracting(Snapshot::takenAt).containsExactly(at);
        assertThat(data.resolve("snapshots")).doesNotExist();
    }

    @Test
    @DisplayName("pruning drops what is older than the cutoff, and a player left with nothing disappears")
    void pruning() {
        UUID busy = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        Instant now = Instant.now();
        SnapshotStore store = snapshots();
        store.saveAll(busy, List.of(taken(busy, now.minus(Duration.ofDays(9))), taken(busy, now.minus(Duration.ofDays(1)))));
        store.saveAll(gone, List.of(taken(gone, now.minus(Duration.ofDays(30)))));

        int dropped = store.pruneOlderThan(now.minus(Duration.ofDays(7)));

        assertThat(dropped).isEqualTo(2);
        assertThat(store.knownPlayerIds()).containsExactly(busy);
        assertThat(store.load(busy)).hasSize(1);
    }

    @Test
    @DisplayName("pruning with nothing too old changes nothing")
    void pruningNothing() {
        UUID player = UUID.randomUUID();
        SnapshotStore store = snapshots();
        store.saveAll(player, List.of(taken(player, Instant.now())));

        assertThat(store.pruneOlderThan(Instant.now().minus(Duration.ofDays(7)))).isZero();
        assertThat(store.load(player)).hasSize(1);
    }

    @Test
    @DisplayName("who is insured survives a restart, from the document")
    void insuredIsADocument() throws Exception {
        UUID player = UUID.randomUUID();
        new InsuredStore(yaml("insured.yml")).set(player, true);

        assertThat(new InsuredStore(yaml("insured.yml")).isInsured(player)).isTrue();
        assertThat(data.resolve("insured.yml")).doesNotExist();
    }

    @Test
    @DisplayName("policies and waiting returns survive a restart, from documents")
    void policiesAreDocuments() {
        UUID owner = UUID.randomUUID();
        ItemPolicy policy = new ItemPolicy("p1", owner, "Diamond Sword", "DIAMOND_SWORD", 5_000, 500, 123_456L, "", false);
        ItemPolicyStore store = new ItemPolicyStore(yaml("item-policies.yml"), yaml("item-returns.yml"));
        store.put(policy);
        store.addReturn(owner, "AAAA");

        ItemPolicyStore again = new ItemPolicyStore(yaml("item-policies.yml"), yaml("item-returns.yml"));
        assertThat(again.get("p1")).isEqualTo(policy);
        assertThat(again.returnsOf(owner)).containsExactly("AAAA");
        assertThat(Files.exists(data.resolve("item-policies.yml"))).isFalse();
    }
}
