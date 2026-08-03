package de.raindancer.modules.moderation;

import de.raindancer.modules.moderation.model.Report;
import de.raindancer.modules.moderation.model.ReportState;
import de.raindancer.modules.moderation.model.StaffNote;
import de.raindancer.modules.moderation.store.NoteStorage;
import de.raindancer.modules.moderation.store.ReportStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That what was written comes back.
 *
 * <h2>Why a round trip rather than a check of the file's shape</h2>
 * Because the failure that matters is not "the file looks wrong", it is "the server restarted and the
 * open reports are gone" — and a test that asserts on YAML keys passes happily while the loader ignores
 * one of them. Every case below is written, read, and compared to what went in.
 */
class StorageTest {

    private final UUID ayla = UUID.randomUUID();
    private final UUID bram = UUID.randomUUID();
    private final UUID cyra = UUID.randomUUID();
    private final Instant when = Instant.parse("2026-08-03T12:00:00Z");

    @Nested
    @DisplayName("reports")
    class Reports {

        @Test
        @DisplayName("an open report survives being written and read")
        void anOpenReport(@TempDir Path folder) throws IOException {
            ReportStorage storage = new ReportStorage(folder);
            Report filed = Report.filed("R1", ayla, "Ayla", bram, "Bram", "griefing my house", when);

            storage.saveAll(List.of(filed));

            assertThat(storage.load()).singleElement().isEqualTo(filed);
        }

        @Test
        @DisplayName("a closed one keeps who closed it and what they decided")
        void aClosedReport(@TempDir Path folder) throws IOException {
            ReportStorage storage = new ReportStorage(folder);
            Report done = Report.filed("R2", ayla, "Ayla", bram, "Bram", "griefing", when)
                    .resolved(cyra, "Cyra", "rolled back", when.plusSeconds(600));

            storage.saveAll(List.of(done));

            Report back = storage.load().getFirst();
            assertThat(back).isEqualTo(done);
            assertThat(back.state()).isEqualTo(ReportState.RESOLVED);
            assertThat(back.closedAt()).isEqualTo(when.plusSeconds(600));
        }

        @Test
        @DisplayName("a report from the console has no reporter and reads back that way")
        void aConsoleReport(@TempDir Path folder) throws IOException {
            ReportStorage storage = new ReportStorage(folder);
            Report filed = Report.filed("R3", null, "the console", bram, "Bram", "automated", when);

            storage.saveAll(List.of(filed));

            assertThat(storage.load()).singleElement().isEqualTo(filed);
        }

        @Test
        @DisplayName("text with colons, quotes and newlines comes back as it was typed")
        void awkwardText(@TempDir Path folder) throws IOException {
            // Players type all of these, and each one has broken a hand-rolled YAML writer somewhere.
            ReportStorage storage = new ReportStorage(folder);
            String awkward = "he said: \"it's mine\"\nand then #griefed it — <red>";
            Report filed = Report.filed("R4", ayla, "Ayla", bram, "Bram", awkward, when);

            storage.saveAll(List.of(filed));

            assertThat(storage.load().getFirst().text()).isEqualTo(awkward);
        }

        @Test
        @DisplayName("nothing on disk is no reports rather than a failure")
        void nothingYet(@TempDir Path folder) {
            assertThat(new ReportStorage(folder).load()).isEmpty();
        }

        @Test
        @DisplayName("an unreadable entry is skipped and the rest still load")
        void oneBadEntry(@TempDir Path folder) throws IOException {
            // One report with a mangled uuid must not cost the server its other forty.
            ReportStorage storage = new ReportStorage(folder);
            UUID dara = UUID.randomUUID();
            storage.saveAll(List.of(
                    Report.filed("R1", ayla, "Ayla", bram, "Bram", "the good one", when),
                    Report.filed("R2", ayla, "Ayla", dara, "Dara", "the broken one", when)));

            String yaml = Files.readString(storage.file(), StandardCharsets.UTF_8);
            Files.writeString(storage.file(), yaml.replace(dara.toString(), "not-a-uuid"),
                    StandardCharsets.UTF_8);

            assertThat(storage.load())
                    .as("the readable report has to survive its neighbour being unreadable")
                    .extracting(Report::id).containsExactly("R1");
        }

        @Test
        @DisplayName("saving nothing empties the file rather than leaving yesterday's reports")
        void savingNothing(@TempDir Path folder) throws IOException {
            ReportStorage storage = new ReportStorage(folder);
            storage.saveAll(List.of(Report.filed("R1", ayla, "Ayla", bram, "Bram", "something", when)));

            storage.saveAll(List.of());

            assertThat(storage.load()).isEmpty();
        }

        @Test
        @DisplayName("the folder is made when it is not there")
        void theFolderIsMade(@TempDir Path folder) throws IOException {
            Path missing = folder.resolve("not").resolve("there");
            ReportStorage storage = new ReportStorage(missing);

            storage.saveAll(List.of(Report.filed("R1", ayla, "A", bram, "B", "something", when)));

            assertThat(storage.load()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("notes")
    class Notes {

        @Test
        @DisplayName("a note survives being written and read")
        void aNote(@TempDir Path folder) throws IOException {
            NoteStorage storage = new NoteStorage(folder);
            StaffNote note = new StaffNote("N1", bram, cyra, "Cyra", "asked about the build twice", when);

            storage.saveAll(List.of(note));

            assertThat(storage.load()).singleElement().isEqualTo(note);
        }

        @Test
        @DisplayName("a note written by the console has no author")
        void aConsoleNote(@TempDir Path folder) throws IOException {
            NoteStorage storage = new NoteStorage(folder);
            StaffNote note = new StaffNote("N2", bram, null, "the console", "imported", when);

            storage.saveAll(List.of(note));

            assertThat(storage.load()).singleElement().isEqualTo(note);
        }

        @Test
        @DisplayName("nothing on disk is no notes rather than a failure")
        void nothingYet(@TempDir Path folder) {
            assertThat(new NoteStorage(folder).load()).isEmpty();
        }

        @Test
        @DisplayName("many notes about many players all come back")
        void several(@TempDir Path folder) throws IOException {
            NoteStorage storage = new NoteStorage(folder);
            List<StaffNote> all = List.of(
                    new StaffNote("N1", bram, cyra, "Cyra", "one", when),
                    new StaffNote("N2", bram, cyra, "Cyra", "two", when.plusSeconds(60)),
                    new StaffNote("N3", ayla, cyra, "Cyra", "three", when.plusSeconds(120)));

            storage.saveAll(all);

            assertThat(storage.load()).containsExactlyInAnyOrderElementsOf(all);
        }
    }
}
