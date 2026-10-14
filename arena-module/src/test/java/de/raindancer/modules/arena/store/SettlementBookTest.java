package de.raindancer.modules.arena.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.store.Documents;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.testkit.TestDatabases;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What survival servers still owe, kept as a document of the arena server and read again after a restart. */
@Tag("database")
class SettlementBookTest {

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

    private Path file() {
        return plugins.resolve("RainsArena").resolve("settlements.yml");
    }

    private SettlementBook book() {
        return new SettlementBook(new YamlStore(file(), documents, Documents.Scope.SERVER));
    }

    @Test
    @DisplayName("one fight owes each survival server its own settlement, and each survives a restart until it is done")
    void perServerAndLasting() {
        Map<String, Long> payouts = new LinkedHashMap<>();
        payouts.put("00000000-0000-0000-0000-00000000000c", 200L);
        SettlementBook first = book();
        first.add(new SettlementBook.Settlement("fight-1", "smp-1", false, payouts, "a,b,c"));
        first.add(new SettlementBook.Settlement("fight-1", "smp.2", true, Map.of(), ""));

        SettlementBook after = book();
        assertThat(after.open()).hasSize(2);
        assertThat(after.open()).anySatisfy(owed -> {
            assertThat(owed.home()).isEqualTo("smp-1");
            assertThat(owed.refund()).isFalse();
            assertThat(owed.payouts()).isEqualTo(payouts);
            assertThat(owed.participants()).isEqualTo("a,b,c");
        });
        assertThat(after.open()).anySatisfy(owed -> {
            assertThat(owed.home()).isEqualTo("smp.2");
            assertThat(owed.refund()).isTrue();
        });

        after.done("fight-1", "smp-1");
        assertThat(book().open()).extracting(SettlementBook.Settlement::home).containsExactly("smp.2");
        after.done("fight-1", "smp.2");
        assertThat(book().open()).isEmpty();
    }

    @Test
    @DisplayName("a settlements.yml written before there were several servers is still owed, to the one server")
    void readsTheOldShape() throws Exception {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), """
                open:
                  fight-9:
                    refund: false
                    payouts:
                      00000000-0000-0000-0000-00000000000a: 150
                  fight-8:
                    refund: true
                """);

        SettlementBook book = book();

        assertThat(book.open()).hasSize(2);
        assertThat(book.open()).allSatisfy(owed -> assertThat(owed.home()).isEmpty());
        assertThat(book.open()).anySatisfy(owed -> {
            assertThat(owed.fight()).isEqualTo("fight-9");
            assertThat(owed.payouts()).containsEntry("00000000-0000-0000-0000-00000000000a", 150L);
        });
        book.done("fight-9", "");
        assertThat(book().open()).extracting(SettlementBook.Settlement::fight).containsExactly("fight-8");
    }
}
