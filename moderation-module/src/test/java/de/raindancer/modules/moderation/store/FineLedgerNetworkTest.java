package de.raindancer.modules.moderation.store;

import de.raindancer.core.data.store.Documents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

import de.raindancer.modules.moderation.model.FineRecord;

@Tag("database")
class FineLedgerNetworkTest {

    @TempDir
    Path plugins;

    private TwoServers servers;
    private final UUID bo = UUID.randomUUID();

    private FineLedger on(String id) {
        FineLedger ledger = new FineLedger(servers.network(servers.server(id), "fines.yml"));
        ledger.load();
        return ledger;
    }

    private FineRecord fine(String id, long debt) {
        return new FineRecord(id, bo, "p-" + id, "fine", "griefing", 1, 1_000, 1_000 - debt, 0, null, debt, 0, false, 0);
    }

    @BeforeEach
    void start() {
        servers = new TwoServers(plugins);
    }

    @AfterEach
    void stop() {
        servers.close();
    }

    @Test
    @DisplayName("a fine given on one server is owed on the other, and what is paid there is paid here")
    void owedEverywhere() {
        FineLedger smp = on("smp");
        FineLedger arena = on("arena");

        smp.add(fine("a", 400));
        servers.settle();
        assertThat(arena.owed(bo)).isEqualTo(400);

        assertThat(arena.payDown(bo, 150)).isEqualTo(150);
        servers.settle();
        assertThat(smp.owed(bo)).isEqualTo(250);
    }

    @Test
    @DisplayName("two servers giving different fines at once: both are in the ledger on both")
    void concurrentFines() {
        FineLedger smp = on("smp");
        FineLedger arena = on("arena");

        smp.add(fine("a", 100));
        arena.add(fine("b", 200));
        servers.settle();
        servers.settle();

        assertThat(smp.owed(bo)).isEqualTo(300);
        assertThat(arena.owed(bo)).isEqualTo(300);
    }
}
