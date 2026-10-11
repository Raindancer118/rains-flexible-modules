package de.raindancer.modules.moderation.rules;

import de.raindancer.modules.moderation.model.Report;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AutoReportRuleTest {

    private final AutoReportRule rule = new AutoReportRule();
    private final UUID cheater = UUID.randomUUID();

    private Report report(UUID subject, String text) {
        return Report.filed("R-" + Math.abs(text.hashCode()), null, null, subject, "Cheater", text, Instant.now());
    }

    @Test
    @DisplayName("the text starts with its kind, the way an x-ray report starts with x-ray")
    void text() {
        assertThat(rule.text("movement", "fly x40 · 40 flags")).isEqualTo("movement: fly x40 · 40 flags");
    }

    @Test
    @DisplayName("with nothing filed about them yet, a report is filed")
    void fresh() {
        assertThat(rule.alreadyWaiting(cheater, "movement", List.of())).isFalse();
    }

    @Test
    @DisplayName("an open report of the same kind about the same player means no second one")
    void sameKindOpen() {
        List<Report> about = List.of(report(cheater, "movement: fly x40"));
        assertThat(rule.alreadyWaiting(cheater, "movement", about)).isTrue();
        assertThat(rule.alreadyWaiting(cheater, "movement", List.of(about.get(0).claimedBy(UUID.randomUUID(), "Mod")))).as("being looked at is still live").isTrue();
    }

    @Test
    @DisplayName("once it is closed, the same player can be reported again")
    void closed() {
        Report closed = report(cheater, "movement: fly x40").resolved(UUID.randomUUID(), "Mod", "banned", Instant.now());
        assertThat(rule.alreadyWaiting(cheater, "movement", List.of(closed))).isFalse();
    }

    @Test
    @DisplayName("another kind, another player or a human's report of the same words do not count")
    void others() {
        assertThat(rule.alreadyWaiting(cheater, "movement", List.of(report(cheater, "x-ray: mining this pattern")))).isFalse();
        assertThat(rule.alreadyWaiting(cheater, "movement", List.of(report(UUID.randomUUID(), "movement: fly x40")))).isFalse();
        Report byHuman = Report.filed("R-human", UUID.randomUUID(), "Alice", cheater, "Cheater", "movement: he flies", Instant.now());
        assertThat(rule.alreadyWaiting(cheater, "movement", List.of(byHuman))).as("only machine reports hold the slot").isFalse();
    }
}
