package de.raindancer.modules.mannequin.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.store.DocumentFolder;
import de.raindancer.core.data.store.Documents;
import de.raindancer.core.testkit.TestDatabases;
import de.raindancer.modules.mannequin.model.Mannequin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Mannequins as documents in Core's database — and the 1.x files still lying in the folder. */
@Tag("database")
class MannequinStoreDocumentsTest {

    private static final String BROKEN = "id: [unclosed\n  : : :\n";

    @TempDir
    Path plugins;

    private Database database;
    private Documents documents;

    @BeforeEach
    void open() {
        database = TestDatabases.open(plugins.resolve("documents.db"), Documents.SCHEMA);
        documents = new Documents(database, null, plugins, "smp", System::currentTimeMillis);
    }

    @AfterEach
    void close() {
        database.close();
    }

    private Path mannequins() {
        return plugins.resolve("RainsMannequins").resolve("mannequins");
    }

    private DocumentFolder documents() {
        return DocumentFolder.on(mannequins(), documents, Documents.Scope.SERVER);
    }

    private MannequinStore store() {
        return new MannequinStore(documents());
    }

    private static Mannequin mannequin(String id) {
        return Mannequin.freshlyPlaced(id, UUID.randomUUID(), "world", 1, 64, 2).withDisplayName("Dummy " + id);
    }

    @Test
    @DisplayName("a mannequin saved comes back whole, from the database and not from a file")
    void roundTrip() {
        Mannequin mannequin = mannequin("MQ1");
        assertThat(store().save(mannequin)).isTrue();

        assertThat(store().loadAll()).singleElement().satisfies(back -> {
            assertThat(back.id()).isEqualTo("MQ1");
            assertThat(back.owner()).isEqualTo(mannequin.owner());
            assertThat(back.displayName()).isEqualTo("Dummy MQ1");
        });
        assertThat(mannequins().resolve("MQ1.yml")).doesNotExist();
    }

    @Test
    @DisplayName("listing is sorted by id, and a deleted mannequin is gone from it")
    void listingAndDelete() {
        store().save(mannequin("MQ2"));
        store().save(mannequin("MQ1"));
        store().save(mannequin("MQ3"));
        assertThat(store().loadAll()).extracting(Mannequin::id).containsExactly("MQ1", "MQ2", "MQ3");

        assertThat(store().delete("MQ2")).isTrue();

        assertThat(store().loadAll()).extracting(Mannequin::id).containsExactly("MQ1", "MQ3");
        assertThat(store().delete("MQ2")).as("deleting what is not there leaves it gone").isTrue();
    }

    @Test
    @DisplayName("a 1.x file is listed, brought in on its first read and renamed")
    void oldFileIsBroughtIn() throws IOException {
        Files.createDirectories(mannequins());
        Files.writeString(mannequins().resolve("MQ9.yml"),
                "id: MQ9\nowner: " + UUID.randomUUID() + "\nworld: world\nx: 3\ny: 70\nz: 4\n");

        assertThat(store().loadAll()).extracting(Mannequin::id).containsExactly("MQ9");

        assertThat(mannequins().resolve("MQ9.yml")).doesNotExist();
        assertThat(store().loadAll()).hasSize(1);
    }

    @Test
    @DisplayName("an unreadable entry is not loaded, not lost and not overwritten")
    void unreadableIsKept() {
        store().save(mannequin("MQ1"));
        assertThat(documents().store("MQ2.yml").writeText(BROKEN)).isTrue();

        assertThat(store().loadAll()).extracting(Mannequin::id).containsExactly("MQ1");

        assertThat(documents().names(".yml")).contains("MQ2.yml");
        assertThat(documents().store("MQ2.yml").readText()).contains(BROKEN);
    }

    @Test
    @DisplayName("an unreadable 1.x file stays a file, untouched")
    void unreadableFileStays() throws IOException {
        Files.createDirectories(mannequins());
        Files.writeString(mannequins().resolve("MQ2.yml"), BROKEN);

        assertThat(store().loadAll()).isEmpty();

        assertThat(mannequins().resolve("MQ2.yml")).exists().hasContent(BROKEN);
    }
}
