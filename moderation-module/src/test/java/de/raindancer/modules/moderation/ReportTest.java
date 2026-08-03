package de.raindancer.modules.moderation;

import de.raindancer.modules.moderation.model.Report;
import de.raindancer.modules.moderation.model.ReportState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a player told the staff, and what happened to it.
 *
 * <h2>Why a report has a state rather than being deleted when it is dealt with</h2>
 * Because "nothing was done about it" and "it was looked at and there was nothing in it" are different
 * answers, and a player who reported something deserves the second one. A closed report keeps who closed
 * it and what they decided, which is also the only way to notice the reporter who files eleven a day.
 */
class ReportTest {

    private final UUID reporter = UUID.randomUUID();
    private final UUID subject = UUID.randomUUID();
    private final UUID staff = UUID.randomUUID();
    private final Instant when = Instant.parse("2026-08-03T12:00:00Z");

    private Report filed() {
        return Report.filed("R1", reporter, "Ayla", subject, "Bram", "griefing my house", when);
    }

    @Nested
    @DisplayName("filing one")
    class Filing {

        @Test
        @DisplayName("a new report is open and unhandled")
        void aNewReportIsOpen() {
            Report report = filed();

            assertThat(report.state()).isEqualTo(ReportState.OPEN);
            assertThat(report.isOpen()).isTrue();
            assertThat(report.handlerId()).isEmpty();
            assertThat(report.closedAt()).isNull();
            assertThat(report.text()).isEqualTo("griefing my house");
        }

        @Test
        @DisplayName("a report needs an id, a subject and something to say")
        void whatIsRequired() {
            assertThatThrownBy(() -> Report.filed(" ", reporter, "Ayla", subject, "Bram", "x", when))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Report.filed("R1", reporter, "Ayla", null, "Bram", "x", when))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Report.filed("R1", reporter, "Ayla", subject, "Bram", "  ", when))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Report.filed("R1", reporter, "Ayla", subject, "Bram", "x", null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("the console may file one, so a reporter is allowed to be absent")
        void theConsoleMayFileOne() {
            Report report = Report.filed("R1", null, "the console", subject, "Bram", "automated", when);

            assertThat(report.reporterId()).isEmpty();
            assertThat(report.isOpen()).isTrue();
        }
    }

    @Nested
    @DisplayName("dealing with one")
    class Handling {

        @Test
        @DisplayName("claiming it names who is on it, and it is no longer waiting")
        void claiming() {
            // Two moderators walking to the same grief is the thing this stops.
            Report claimed = filed().claimedBy(staff, "Cyra");

            assertThat(claimed.state()).isEqualTo(ReportState.CLAIMED);
            assertThat(claimed.handlerId()).contains(staff);
            assertThat(claimed.handlerName()).isEqualTo("Cyra");
            assertThat(claimed.isOpen()).isFalse();
            assertThat(claimed.isWaiting()).isFalse();
            assertThat(claimed.closedAt()).isNull();
        }

        @Test
        @DisplayName("a claimed report can be handed back")
        void releasing() {
            Report back = filed().claimedBy(staff, "Cyra").released();

            assertThat(back.state()).isEqualTo(ReportState.OPEN);
            assertThat(back.handlerId()).isEmpty();
            assertThat(back.isWaiting()).isTrue();
        }

        @Test
        @DisplayName("resolving records who, when and what they decided")
        void resolving() {
            Report done = filed().resolved(staff, "Cyra", "rolled back, warned", when.plusSeconds(600));

            assertThat(done.state()).isEqualTo(ReportState.RESOLVED);
            assertThat(done.isClosed()).isTrue();
            assertThat(done.handlerId()).contains(staff);
            assertThat(done.outcome()).isEqualTo("rolled back, warned");
            assertThat(done.closedAt()).isEqualTo(when.plusSeconds(600));
        }

        @Test
        @DisplayName("rejecting is a decision too, and says so")
        void rejecting() {
            Report no = filed().rejected(staff, "Cyra", "nothing there", when.plusSeconds(60));

            assertThat(no.state()).isEqualTo(ReportState.REJECTED);
            assertThat(no.isClosed()).isTrue();
            assertThat(no.outcome()).isEqualTo("nothing there");
        }

        @Test
        @DisplayName("closing it does not change what was reported")
        void theReportItselfIsNotRewritten() {
            // A moderator's outcome sits beside the report, never over it. Otherwise "what did they
            // actually say" has no answer once somebody has typed into the same field.
            Report original = filed();
            Report done = original.resolved(staff, "Cyra", "handled", when.plusSeconds(60));

            assertThat(done.text()).isEqualTo(original.text());
            assertThat(done.at()).isEqualTo(original.at());
            assertThat(done.reporterId()).isEqualTo(original.reporterId());
            assertThat(done.id()).isEqualTo(original.id());
        }

        @Test
        @DisplayName("an empty outcome is recorded as one rather than as nothing")
        void anEmptyOutcome() {
            Report done = filed().resolved(staff, "Cyra", "  ", when);

            assertThat(done.outcome()).isNotBlank();
        }

        @Test
        @DisplayName("a report is a value, so nothing that reads it can change it")
        void itIsAValue() {
            Report original = filed();

            original.claimedBy(staff, "Cyra");

            assertThat(original.state()).isEqualTo(ReportState.OPEN);
        }
    }

    @Nested
    @DisplayName("its state")
    class States {

        @Test
        @DisplayName("open and claimed are live; resolved and rejected are closed")
        void whichAreClosed() {
            assertThat(ReportState.OPEN.isClosed()).isFalse();
            assertThat(ReportState.CLAIMED.isClosed()).isFalse();
            assertThat(ReportState.RESOLVED.isClosed()).isTrue();
            assertThat(ReportState.REJECTED.isClosed()).isTrue();
        }

        @Test
        @DisplayName("every state says what it is, so no screen has to spell out an enum name")
        void everyStateDescribesItself() {
            for (ReportState state : ReportState.values()) {
                assertThat(state.describe()).isNotBlank();
                assertThat(state.icon()).isNotNull();
            }
        }
    }
}
