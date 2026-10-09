package de.raindancer.modules.performance.store;

import de.raindancer.modules.performance.model.Report;
import de.raindancer.modules.performance.rules.LagRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReportStoreTest {

    @TempDir
    Path folder;

    private static Report report(int number) {
        return new Report(number, Instant.EPOCH, "asked for", LagRule.State.LAGGING, 60, 70, 90, 16.6, 10,
                List.of(), List.of(), List.of());
    }

    @Test
    @DisplayName("each report is written to its own file and can be looked up by its number")
    void writes() throws IOException {
        ReportStore store = new ReportStore(folder, () -> 10);
        int number = store.nextNumber();
        store.add(report(number));

        assertThat(store.get(number)).isPresent();
        assertThat(Files.readString(folder.resolve("report-" + number + ".txt"))).contains("Report #" + number);
    }

    @Test
    @DisplayName("numbers go on after a restart instead of overwriting yesterday's reports")
    void numbering() throws IOException {
        Files.writeString(folder.resolve("report-41.txt"), "old");
        Files.writeString(folder.resolve("report-7.txt"), "older");
        Files.writeString(folder.resolve("notes.txt"), "not a report");

        assertThat(new ReportStore(folder, () -> 10).nextNumber()).isEqualTo(42);
    }

    @Test
    @DisplayName("only the newest are kept, in memory and on disk")
    void prunes() {
        ReportStore store = new ReportStore(folder, () -> 2);
        for (int i = 0; i < 4; i++) {
            store.add(report(store.nextNumber()));
        }

        assertThat(store.recent()).extracting(Report::number).containsExactly(4, 3);
        assertThat(Files.exists(folder.resolve("report-1.txt"))).isFalse();
        assertThat(Files.exists(folder.resolve("report-2.txt"))).isFalse();
        assertThat(Files.exists(folder.resolve("report-4.txt"))).isTrue();
        assertThat(store.get(1)).isEmpty();
    }
}
