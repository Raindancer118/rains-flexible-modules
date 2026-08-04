package de.raindancer.modules.moderation;

import de.raindancer.core.moderation.punishment.Punishment;
import de.raindancer.core.moderation.punishment.PunishmentKind;
import de.raindancer.modules.moderation.model.RecordEntry;
import de.raindancer.modules.moderation.model.Report;
import de.raindancer.modules.moderation.model.ReportState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That "the entire record" is the entire record.
 *
 * <h2>The defect this exists because of</h2>
 * The record screen listed punishments and nothing else. Reports lived on their own screen, and neither
 * screen said the other existed — so somebody reported four times and never punished read as spotless,
 * to a moderator who had opened the page called "Record" precisely to avoid missing something.
 *
 * <p>A record that leaves half of itself out is worse than no record, because it is trusted.
 */
class RecordEntryTest {

    private static final UUID THEM = UUID.randomUUID();
    private static final UUID MOD = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-04T02:00:00Z");

    private static Punishment punishment(Instant at) {
        return new Punishment(UUID.randomUUID().toString(), THEM, PunishmentKind.MUTE, MOD,
                "spam", at, null, null, null, null);
    }

    private static Report report(Instant at) {
        return new Report("R1", UUID.randomUUID(), "Reporter", THEM, "Them", "griefing", at,
                ReportState.OPEN, null, null, null, null);
    }

    @Test
    @DisplayName("both kinds end up in one list")
    void bothKinds() {
        List<RecordEntry> record = RecordEntry.merge(
                List.of(punishment(NOW)), List.of(report(NOW.minus(Duration.ofHours(1)))));

        assertThat(record).hasSize(2);
        assertThat(record).hasAtLeastOneElementOfType(RecordEntry.Punished.class);
        assertThat(record).hasAtLeastOneElementOfType(RecordEntry.Reported.class);
    }

    @Test
    @DisplayName("newest first, with the two kinds interleaved by time")
    void newestFirst() {
        // Interleaved rather than grouped: a report filed the day after a warning is the story, and
        // grouping by kind hides the sequence that makes it one.
        Punishment old = punishment(NOW.minus(Duration.ofDays(3)));
        Report middle = report(NOW.minus(Duration.ofDays(2)));
        Punishment recent = punishment(NOW.minus(Duration.ofHours(1)));

        List<RecordEntry> record = RecordEntry.merge(List.of(old, recent), List.of(middle));

        assertThat(record).extracting(RecordEntry::at).containsExactly(
                recent.givenAt(), middle.at(), old.givenAt());
    }

    @Test
    @DisplayName("a report stays a report — an accusation is not a finding")
    void kindsStayApart() {
        // Four reports from one angry player must not read like four findings of guilt.
        List<RecordEntry> record = RecordEntry.merge(List.of(), List.of(report(NOW)));

        assertThat(record.getFirst()).isInstanceOf(RecordEntry.Reported.class);
        assertThat(((RecordEntry.Reported) record.getFirst()).report().text()).isEqualTo("griefing");
    }

    @Test
    @DisplayName("nothing at all is an empty record, not a crash")
    void nothing() {
        assertThat(RecordEntry.merge(List.of(), List.of())).isEmpty();
        assertThat(RecordEntry.merge(null, null)).isEmpty();
    }

    @Test
    @DisplayName("only punishments, or only reports, both work")
    void oneSided() {
        assertThat(RecordEntry.merge(List.of(punishment(NOW)), null)).hasSize(1);
        assertThat(RecordEntry.merge(null, List.of(report(NOW)))).hasSize(1);
    }

    @Test
    @DisplayName("a punishment carries who gave it, so the screen can say")
    void whoDidIt() {
        // The record showed what happened and never who did it, which is the first thing anybody asks
        // when they disagree with an entry.
        RecordEntry entry = RecordEntry.merge(List.of(punishment(NOW)), List.of()).getFirst();

        assertThat(((RecordEntry.Punished) entry).punishment().moderator()).isEqualTo(MOD);
    }
}
