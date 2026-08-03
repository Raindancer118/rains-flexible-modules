package de.raindancer.modules.moderation;

import de.raindancer.modules.moderation.model.Report;
import de.raindancer.modules.moderation.store.ReportRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What holds the reports while the server is up.
 *
 * <p>The index, not the file. The file is {@code ReportStorage}'s business and this never touches it —
 * which is what lets every question below be asked in a test without a disk.
 */
class ReportRegistryTest {

    private final UUID ayla = UUID.randomUUID();
    private final UUID bram = UUID.randomUUID();
    private final UUID cyra = UUID.randomUUID();
    private final Instant when = Instant.parse("2026-08-03T12:00:00Z");

    private final ReportRegistry reports = new ReportRegistry();

    private Report report(String id, UUID reporter, UUID subject, Instant at) {
        return Report.filed(id, reporter, "someone", subject, "someone else", "something happened", at);
    }

    @Nested
    @DisplayName("holding them")
    class Holding {

        @Test
        @DisplayName("what goes in comes back out by its id")
        void byId() {
            reports.add(report("R1", ayla, bram, when));

            assertThat(reports.byId("R1")).isPresent();
            assertThat(reports.byId("r1")).as("ids are matched however they are typed").isPresent();
            assertThat(reports.byId("R2")).isEmpty();
            assertThat(reports.byId(null)).isEmpty();
        }

        @Test
        @DisplayName("adding the same id twice replaces rather than duplicates")
        void addingReplaces() {
            reports.add(report("R1", ayla, bram, when));
            reports.add(report("R1", ayla, bram, when).claimedBy(cyra, "Cyra"));

            assertThat(reports.size()).isOne();
            assertThat(reports.byId("R1").orElseThrow().handlerId()).contains(cyra);
        }

        @Test
        @DisplayName("one can be taken out again")
        void removing() {
            reports.add(report("R1", ayla, bram, when));

            assertThat(reports.remove("R1")).isTrue();
            assertThat(reports.remove("R1")).isFalse();
            assertThat(reports.size()).isZero();
        }
    }

    @Nested
    @DisplayName("asking about them")
    class Asking {

        @Test
        @DisplayName("the waiting ones are the ones nobody has claimed")
        void waiting() {
            reports.add(report("R1", ayla, bram, when));
            reports.add(report("R2", ayla, cyra, when).claimedBy(cyra, "Cyra"));
            reports.add(report("R3", ayla, cyra, when).resolved(cyra, "Cyra", "done", when));

            assertThat(reports.waiting()).extracting(Report::id).containsExactly("R1");
            assertThat(reports.live()).extracting(Report::id).containsExactlyInAnyOrder("R1", "R2");
            assertThat(reports.waitingCount()).isOne();
        }

        @Test
        @DisplayName("newest first, because that is the order a queue is read in")
        void newestFirst() {
            reports.add(report("R1", ayla, bram, when.minusSeconds(600)));
            reports.add(report("R2", ayla, bram, when.minusSeconds(60)));
            reports.add(report("R3", ayla, bram, when));

            assertThat(reports.all()).extracting(Report::id).containsExactly("R3", "R2", "R1");
        }

        @Test
        @DisplayName("what somebody has been reported for, and what they have reported")
        void aboutAndBy() {
            reports.add(report("R1", ayla, bram, when));
            reports.add(report("R2", bram, ayla, when));

            assertThat(reports.about(bram)).extracting(Report::id).containsExactly("R1");
            assertThat(reports.by(ayla)).extracting(Report::id).containsExactly("R1");
            assertThat(reports.about(null)).isEmpty();
            assertThat(reports.by(null)).isEmpty();
        }

        @Test
        @DisplayName("a list handed out cannot be used to change what is held")
        void listsAreCopies() {
            reports.add(report("R1", ayla, bram, when));
            List<Report> all = reports.all();

            assertThat(all).hasSize(1);
            all.clear();

            assertThat(reports.size()).as("a caller emptying its own copy must not empty the registry")
                    .isOne();
        }
    }

    @Nested
    @DisplayName("numbering them")
    class Numbering {

        @Test
        @DisplayName("ids are handed out in order and never repeat")
        void idsAreUnique() {
            String first = reports.nextId();
            reports.add(report(first, ayla, bram, when));
            String second = reports.nextId();

            assertThat(second).isNotEqualTo(first);
        }

        @Test
        @DisplayName("after a reload the numbering carries on rather than starting again")
        void numberingSurvivesAReload() {
            // Otherwise the first report after a restart is called R1 again, and "R1" in yesterday's
            // console log means two different things.
            reports.add(report("R7", ayla, bram, when));
            reports.add(report("R3", ayla, bram, when));

            String next = reports.nextId();

            assertThat(next).isEqualTo("R8");
            assertThat(reports.byId(next)).isEmpty();
        }

        @Test
        @DisplayName("an id that is not a number does not stop the counter")
        void oddIdsAreSurvived() {
            reports.add(report("imported-from-somewhere", ayla, bram, when));

            assertThat(reports.nextId()).isNotBlank();
        }
    }

    @Test
    @DisplayName("clearing empties it, and the numbering with it")
    void clearing() {
        reports.add(report("R4", ayla, bram, when));
        reports.clear();

        assertThat(reports.size()).isZero();
        assertThat(reports.all()).isEmpty();
    }
}
