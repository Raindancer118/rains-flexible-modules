package de.raindancer.modules.invsnap.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.store.Documents;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.network.LocalBus;
import de.raindancer.core.testkit.TestDatabases;
import de.raindancer.modules.invsnap.model.ItemPolicy;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** Two servers of one network over one database: insurance opt-ins, policies and waiting items are the network's. */
@Tag("database")
class InvSnapAcrossServersTest {

    @TempDir
    Path plugins;

    private final LocalBus.Hub hub = new LocalBus.Hub();
    private final List<Database> databases = new ArrayList<>();
    private final UUID tom = UUID.randomUUID();
    private final UUID ana = UUID.randomUUID();

    private final class Server {
        final String id;
        final Documents documents;
        final Path data;
        final ItemReturns returns;

        Server(String id) {
            this.id = id;
            Database documentsDatabase = TestDatabases.open(plugins.resolve("documents.db"), Documents.SCHEMA);
            Database returnsDatabase = TestDatabases.open(plugins.resolve("invsnap.db"), ItemReturns.SCHEMA);
            databases.add(documentsDatabase);
            databases.add(returnsDatabase);
            documents = new Documents(documentsDatabase, hub.bus(id), plugins, id, System::currentTimeMillis);
            data = plugins.resolve("RainsInvSnap");
            returns = new ItemReturns(returnsDatabase, System::currentTimeMillis);
            returns.connect(hub.bus(id + "-returns"));
        }

        YamlStore network(String name) {
            return new YamlStore(data.resolve(name), documents, Documents.Scope.NETWORK);
        }

        YamlStore own(String name) {
            return new YamlStore(data.resolve(name), documents, Documents.Scope.SERVER);
        }

        InsuredStore insured() {
            return new InsuredStore(network("insured.yml"));
        }

        ItemPolicyStore policies() {
            return new ItemPolicyStore(network("item-policies.yml"), own("item-returns.yml"), returns);
        }
    }

    @AfterEach
    void close() {
        databases.forEach(Database::close);
    }

    private static ItemPolicy policy(String id, UUID owner, long nextDue) {
        return new ItemPolicy(id, owner, "Diamond Sword", "DIAMOND_SWORD", 5_000, 500, nextDue, "", false);
    }

    @Test
    @DisplayName("opting in on one server insures a death on the other; opting out follows too")
    void insuranceFollowsThePlayer() {
        Server smp = new Server("smp");
        Server arena = new Server("arena");
        InsuredStore onSmp = smp.insured();
        InsuredStore onArena = arena.insured();

        assertThat(onSmp.set(tom, true)).isTrue();
        hub.drain();
        assertThat(onArena.isInsured(tom)).isTrue();

        assertThat(onArena.set(tom, false)).isTrue();
        hub.drain();
        assertThat(onSmp.isInsured(tom)).isFalse();
    }

    @Test
    @DisplayName("two players opting in at once on different servers are both kept")
    void concurrentOptInsAreBothKept() {
        Server smp = new Server("smp");
        Server arena = new Server("arena");
        InsuredStore onSmp = smp.insured();
        InsuredStore onArena = arena.insured();

        onSmp.set(tom, true);
        onArena.set(ana, true);
        hub.drain();

        assertThat(onSmp.isInsured(ana)).isTrue();
        assertThat(onArena.isInsured(tom)).isTrue();
    }

    @Test
    @DisplayName("a 1.x list of insured players becomes one entry each, and the list is gone")
    void legacyListMigrates() throws IOException {
        Files.createDirectories(plugins.resolve("RainsInvSnap"));
        YamlConfiguration old = new YamlConfiguration();
        old.set("players", List.of(tom.toString(), "not-a-uuid", ana.toString()));
        old.save(plugins.resolve("RainsInvSnap").resolve("insured.yml").toFile());
        Server smp = new Server("smp");

        InsuredStore store = smp.insured();

        assertThat(store.isInsured(tom)).isTrue();
        assertThat(store.isInsured(ana)).isTrue();
        assertThat(smp.network("insured.yml").read().contains("players")).isFalse();
        assertThat(smp.insured().isInsured(tom)).isTrue();
    }

    @Test
    @DisplayName("a policy bought on one server is in force on the other, and a premium is claimed by one server only")
    void policiesAreTheNetworks() {
        Server smp = new Server("smp");
        Server arena = new Server("arena");
        ItemPolicyStore onSmp = smp.policies();
        ItemPolicyStore onArena = arena.policies();

        assertThat(onSmp.put(policy("p1", tom, 1_000))).isTrue();
        hub.drain();
        assertThat(onArena.inForceOf(tom)).extracting(ItemPolicy::id).containsExactly("p1");

        boolean first = onSmp.claimDue("p1", 1_000, 2_000);
        boolean second = onArena.claimDue("p1", 1_000, 2_000);

        assertThat(first).isTrue();
        assertThat(second).as("the other server finds it already moved on").isFalse();
        assertThat(onArena.get("p1").nextDue()).isEqualTo(2_000);
    }

    @Test
    @DisplayName("an item put on the list on one server is waiting on the other")
    void waitingItemsAreVisibleEverywhere() {
        Server smp = new Server("smp");
        Server arena = new Server("arena");
        ItemPolicyStore onSmp = smp.policies();
        ItemPolicyStore onArena = arena.policies();

        assertThat(onSmp.addReturn(tom, "AAAA")).isTrue();
        hub.drain();

        assertThat(onArena.returnsOf(tom)).containsExactly("AAAA");
        assertThat(onArena.returns().fresh(tom).orElseThrow()).hasSize(1);
        assertThat(onArena.returnsOf(ana)).isEmpty();
    }

    @Test
    @DisplayName("an item claimed by both servers at once is handed to exactly one")
    void claimedExactlyOnce() throws Exception {
        Server smp = new Server("smp");
        Server arena = new Server("arena");
        smp.policies().addReturn(tom, "AAAA");
        long id = smp.returns.fresh(tom).orElseThrow().getFirst().id();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            Future<Integer> a = pool.submit(() -> claim(go, smp, id));
            Future<Integer> b = pool.submit(() -> claim(go, arena, id));
            go.countDown();

            assertThat(a.get() + b.get()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        hub.drain();
        assertThat(smp.returns.fresh(tom).orElseThrow()).isEmpty();
        assertThat(arena.returns.of(tom)).isEmpty();
    }

    private int claim(CountDownLatch go, Server server, long id) throws InterruptedException {
        go.await();
        return server.returns.claim(tom, List.of(id)).orElseThrow().size();
    }

    @Test
    @DisplayName("somebody else's item cannot be claimed under the wrong owner")
    void claimIsForTheOwner() {
        Server smp = new Server("smp");
        smp.policies().addReturn(tom, "AAAA");
        long id = smp.returns.fresh(tom).orElseThrow().getFirst().id();

        assertThat(smp.returns.claim(ana, List.of(id)).orElseThrow()).isEmpty();
        assertThat(smp.returns.fresh(tom).orElseThrow()).hasSize(1);
    }

    @Test
    @DisplayName("1.x item-returns.yml moves onto the shared list, once, and the file's list is cleared")
    void legacyReturnsMigrate() throws IOException {
        Files.createDirectories(plugins.resolve("RainsInvSnap"));
        YamlConfiguration old = new YamlConfiguration();
        old.set("returns." + tom, List.of("AAAA", "BBBB"));
        old.set("returns." + ana, List.of("CCCC"));
        old.save(plugins.resolve("RainsInvSnap").resolve("item-returns.yml").toFile());
        Server smp = new Server("smp");
        Server arena = new Server("arena");

        ItemPolicyStore onSmp = smp.policies();
        hub.drain();
        ItemPolicyStore again = smp.policies();

        assertThat(onSmp.returnsOf(tom)).containsExactly("AAAA", "BBBB");
        assertThat(onSmp.returnsOf(ana)).containsExactly("CCCC");
        assertThat(again.returnsOf(tom)).as("not imported a second time").containsExactly("AAAA", "BBBB");
        assertThat(arena.policies().returnsOf(tom)).containsExactly("AAAA", "BBBB");
    }
}
