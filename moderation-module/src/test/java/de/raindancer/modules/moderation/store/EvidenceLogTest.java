package de.raindancer.modules.moderation.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.network.LocalBus;
import de.raindancer.core.testkit.TestDatabases;
import de.raindancer.modules.moderation.model.Evidence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Two servers over one database: a cheater's record is the same record on both. */
@Tag("database")
class EvidenceLogTest {

    @TempDir
    Path folder;

    private final LocalBus.Hub hub = new LocalBus.Hub();
    private final List<Database> databases = new ArrayList<>();
    private final List<AutoCloseable> open = new ArrayList<>();
    private final UUID cheater = UUID.randomUUID();

    private EvidenceLog server(String id, int capacity) {
        Database database = TestDatabases.open(folder.resolve("anticheat.db"), AntiCheatDatabase.SCHEMA);
        databases.add(database);
        LocalBus bus = hub.bus(id);
        EvidenceLog log = new EvidenceLog(database, bus, capacity);
        log.load();
        open.add(log.connect(bus));
        return log;
    }

    private static Evidence flag(long at, String check) {
        return new Evidence(at, check, 3.5, "a | detail", "world", 1, 64, -2, 40, 19.5);
    }

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable each : open) {
            each.close();
        }
        databases.forEach(Database::close);
    }

    @Test
    @DisplayName("evidence written on one server is read on the other, newest first, with the name")
    void reachesTheOtherServer() {
        EvidenceLog smp = server("smp", 50);
        EvidenceLog arena = server("arena", 50);

        smp.add(cheater, "Cheater", flag(1000, "fly"));
        smp.add(cheater, "Cheater", flag(2000, "speed"));
        assertThat(smp.flush()).isTrue();
        hub.drain();

        assertThat(arena.of(cheater)).extracting(Evidence::check).containsExactly("speed", "fly");
        assertThat(arena.of(cheater).getFirst()).isEqualTo(flag(2000, "speed"));
        assertThat(arena.nameOf(cheater)).isEqualTo("Cheater");
        assertThat(arena.everybody()).containsExactly(cheater);
    }

    @Test
    @DisplayName("both servers adding at once keep both servers' evidence")
    void bothKeepTheirs() {
        EvidenceLog smp = server("smp", 50);
        EvidenceLog arena = server("arena", 50);

        smp.add(cheater, "Cheater", flag(1000, "fly"));
        arena.add(cheater, "Cheater", flag(2000, "speed"));
        assertThat(smp.flush()).isTrue();
        assertThat(arena.flush()).isTrue();
        hub.drain();

        assertThat(smp.of(cheater)).extracting(Evidence::check).containsExactly("speed", "fly");
        assertThat(arena.of(cheater)).extracting(Evidence::check).containsExactly("speed", "fly");
    }

    @Test
    @DisplayName("what this server has not written yet survives another server's news")
    void unwrittenSurvivesARead() {
        EvidenceLog smp = server("smp", 50);
        EvidenceLog arena = server("arena", 50);
        smp.add(cheater, "Cheater", flag(1000, "fly"));

        arena.add(cheater, "Cheater", flag(2000, "speed"));
        arena.flush();
        hub.drain();

        assertThat(smp.of(cheater)).extracting(Evidence::check).containsExactly("speed", "fly");
        assertThat(smp.flush()).isTrue();
        hub.drain();
        assertThat(arena.of(cheater)).extracting(Evidence::check).containsExactly("speed", "fly");
    }

    @Test
    @DisplayName("only the newest few per player are kept, in the database too")
    void capacity() {
        EvidenceLog smp = server("smp", 3);
        for (int at = 1; at <= 5; at++) {
            smp.add(cheater, "Cheater", flag(at * 1000L, "fly"));
        }
        smp.flush();

        EvidenceLog later = server("later", 3);
        assertThat(later.of(cheater)).extracting(Evidence::atMillis).containsExactly(5000L, 4000L, 3000L);
    }

    @Test
    @DisplayName("a fresh start wipes the player's evidence and date on every server")
    void clearTravels() {
        EvidenceLog smp = server("smp", 50);
        EvidenceLog arena = server("arena", 50);
        smp.add(cheater, "Cheater", flag(1000, "fly"));
        smp.measureFrom(cheater, 900);
        smp.flush();
        hub.drain();
        assertThat(arena.measuredFrom(cheater)).isEqualTo(900);

        arena.clear(cheater);
        assertThat(arena.flush()).isTrue();
        hub.drain();

        assertThat(smp.of(cheater)).isEmpty();
        assertThat(smp.measuredFrom(cheater)).isZero();
        assertThat(server("later", 50).of(cheater)).isEmpty();
    }

    @Test
    @DisplayName("measuring afresh keeps the history and the date, across servers and restarts")
    void measuredFromTravels() {
        EvidenceLog smp = server("smp", 50);
        EvidenceLog arena = server("arena", 50);
        smp.add(cheater, "Cheater", flag(1000, "fly"));
        smp.measureFrom(cheater, 4000);
        smp.flush();
        hub.drain();

        assertThat(arena.of(cheater)).hasSize(1);
        assertThat(arena.since(cheater)).isEmpty();
        assertThat(server("later", 50).measuredFrom(cheater)).isEqualTo(4000);
    }

    @Test
    @DisplayName("the 1.x evidence.yml comes across once: flags, name and fresh-start date; a clear is not undone at restart")
    void importsOldEvidence() throws Exception {
        Path file = folder.resolve("evidence.yml");
        Files.writeString(file, "players:\n  " + cheater + ":\n    name: Cheater\n    measured-from: 1500\n    flags:\n      - '"
                + flag(1000, "fly").encode() + "'\n      - '" + flag(2000, "speed").encode() + "'\n  nonsense:\n    flags: []\n");
        EvidenceLog smp = server("smp", 50);

        smp.importLegacy(new YamlStore(file));
        smp.load();
        assertThat(smp.of(cheater)).extracting(Evidence::check).containsExactly("speed", "fly");
        assertThat(smp.nameOf(cheater)).isEqualTo("Cheater");
        assertThat(smp.measuredFrom(cheater)).isEqualTo(1500);

        smp.clear(cheater);
        smp.flush();
        smp.importLegacy(new YamlStore(file));
        smp.load();
        assertThat(smp.of(cheater)).isEmpty();
    }

    @Test
    @DisplayName("a database that cannot be written keeps the evidence for the next try")
    void failedWriteIsKept() throws Exception {
        Path elsewhere = folder.resolve("down").resolve("anticheat.db");
        Files.createDirectories(elsewhere.getParent());
        Database down = TestDatabases.unusable(elsewhere, AntiCheatDatabase.SCHEMA);
        try {
            EvidenceLog log = new EvidenceLog(down, null, 50);
            log.add(cheater, "Cheater", flag(1000, "fly"));
            assertThat(log.flush()).isFalse();
            assertThat(log.of(cheater)).as("still known here").hasSize(1);
            log.load();
            assertThat(log.of(cheater)).as("an unreadable database does not empty the memory").hasSize(1);
        } finally {
            down.close();
        }
    }
}
