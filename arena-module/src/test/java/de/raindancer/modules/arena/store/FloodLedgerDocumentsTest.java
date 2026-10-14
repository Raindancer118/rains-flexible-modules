package de.raindancer.modules.arena.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.store.DocumentFolder;
import de.raindancer.core.data.store.Documents;
import de.raindancer.core.testkit.TestDatabases;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The flood ledger as documents of this server, and the plain files an older arena left. */
@Tag("database")
class FloodLedgerDocumentsTest {

    @TempDir
    Path plugins;

    private Database database;
    private Documents documents;

    @BeforeEach
    void open() {
        database = TestDatabases.open(plugins.resolve("documents.db"), Documents.SCHEMA);
        documents = new Documents(database, null, plugins, "arena", System::currentTimeMillis);
    }

    @AfterEach
    void close() {
        database.close();
    }

    private Path floods() {
        return plugins.resolve("RainsArena").resolve("floods");
    }

    private FloodLedger ledger() {
        return new FloodLedger(DocumentFolder.on(floods(), documents, Documents.Scope.SERVER), floods());
    }

    @Test
    @DisplayName("what a flood changed is in the database before the water is, and read back whole after a restart")
    void roundTrip() {
        List<FloodLedger.Change> changes = List.of(
                new FloodLedger.Change("arena", -3, 67, 12, "minecraft:air"),
                new FloodLedger.Change("arena", 0, 66, 0,
                        "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]"));
        assertThat(ledger().write("colosseum", changes)).isTrue();

        FloodLedger after = ledger();
        assertThat(after.pending()).containsExactly("colosseum");
        assertThat(after.read("colosseum")).containsExactlyElementsOf(changes);
        assertThat(floods()).doesNotExist();

        after.done("colosseum");
        assertThat(after.pending()).isEmpty();
        assertThat(after.read("colosseum")).isEmpty();
    }

    @Test
    @DisplayName("a ledger an older arena wrote as a file is brought in and kept as *.imported-2.0.0")
    void importsTheOldFiles() throws Exception {
        Files.createDirectories(floods());
        Files.writeString(floods().resolve("pit1.flood"), "arena 1 64 2 minecraft:short_grass\narena 2 64 2 minecraft:air\n");

        FloodLedger ledger = ledger();

        assertThat(ledger.pending()).containsExactly("pit1");
        assertThat(ledger.read("pit1")).containsExactly(
                new FloodLedger.Change("arena", 1, 64, 2, "minecraft:short_grass"),
                new FloodLedger.Change("arena", 2, 64, 2, "minecraft:air"));
        assertThat(floods().resolve("pit1.flood")).doesNotExist();
        assertThat(floods().resolve("pit1.flood.imported-2.0.0")).exists();
    }

    @Test
    @DisplayName("a pit the old file and the database both know is not overwritten by the file")
    void databaseWins() throws Exception {
        assertThat(ledger().write("pit1", List.of(new FloodLedger.Change("arena", 9, 9, 9, "minecraft:stone")))).isTrue();
        Files.createDirectories(floods());
        Files.writeString(floods().resolve("pit1.flood"), "arena 1 64 2 minecraft:air\n");

        FloodLedger ledger = ledger();

        assertThat(ledger.read("pit1")).containsExactly(new FloodLedger.Change("arena", 9, 9, 9, "minecraft:stone"));
    }
}
