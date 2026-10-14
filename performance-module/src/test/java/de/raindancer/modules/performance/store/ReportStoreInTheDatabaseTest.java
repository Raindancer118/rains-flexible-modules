package de.raindancer.modules.performance.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.store.DocumentFolder;
import de.raindancer.core.data.store.Documents;
import de.raindancer.core.testkit.TestDatabases;
import de.raindancer.modules.performance.model.Report;
import de.raindancer.modules.performance.rules.LagRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("database")
@DisplayName("reports are documents in the database, listed, numbered and pruned as the files were")
class ReportStoreInTheDatabaseTest {

    @TempDir
    Path plugins;

    private Database database;
    private Documents documents;
    private Path reports;

    @BeforeEach
    void open() {
        database = TestDatabases.open(plugins.resolve("documents.db"), Documents.SCHEMA);
        documents = new Documents(database, null, plugins, "smp", System::currentTimeMillis);
        reports = plugins.resolve("RainsPerformance").resolve("reports");
    }

    @AfterEach
    void close() {
        database.close();
    }

    private ReportStore store(int keep) {
        return new ReportStore(DocumentFolder.on(reports, documents, Documents.Scope.SERVER), () -> keep);
    }

    private DocumentFolder folder() {
        return DocumentFolder.on(reports, documents, Documents.Scope.SERVER);
    }

    private static Report report(int number) {
        return new Report(number, Instant.EPOCH, "asked for", LagRule.State.LAGGING, 60, 70, 90, 16.6, 10,
                List.of(), List.of(), List.of());
    }

    @Test
    @DisplayName("a report is a document holding its text, and no file is written")
    void written() {
        ReportStore store = store(10);
        int number = store.nextNumber();
        store.add(report(number));

        assertThat(folder().names(".txt")).containsExactly("report-" + number + ".txt");
        assertThat(folder().store("report-" + number + ".txt").readText()).hasValueSatisfying(
                text -> assertThat(text).contains("Report #" + number));
        assertThat(reports).doesNotExist();
    }

    @Test
    @DisplayName("numbers go on after a restart, counting the documents and the old report files")
    void numbering() throws Exception {
        store(10).add(report(41));
        Files.createDirectories(reports);
        Files.writeString(reports.resolve("report-57.txt"), "from 1.x");
        Files.writeString(reports.resolve("notes.txt"), "not a report");

        assertThat(store(10).nextNumber()).isEqualTo(58);
    }

    @Test
    @DisplayName("only the newest are kept: the older documents are deleted")
    void prunes() {
        ReportStore store = store(2);
        for (int i = 0; i < 4; i++) {
            store.add(report(store.nextNumber()));
        }

        assertThat(store.recent()).extracting(Report::number).containsExactly(4, 3);
        assertThat(folder().names(".txt")).containsExactly("report-3.txt", "report-4.txt");
    }
}
