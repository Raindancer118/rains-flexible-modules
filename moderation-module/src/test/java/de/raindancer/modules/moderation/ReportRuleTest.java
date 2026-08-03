package de.raindancer.modules.moderation;

import de.raindancer.modules.moderation.model.Report;
import de.raindancer.modules.moderation.rules.ReportRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether this report should be taken.
 *
 * <h2>Why a report needs a rule at all</h2>
 * Because the report queue is the one part of a moderation system a player can write to, and a queue
 * anybody can fill is a queue nobody reads. Every refusal here is one that was learnt from a real
 * server: the one-word report, the same grief filed four times in a minute, and the player who found
 * that reporting somebody put their name in front of the staff.
 */
class ReportRuleTest {

    private final ReportRule rule = new ReportRule(Duration.ofMinutes(2), 3);
    private final UUID reporter = UUID.randomUUID();
    private final UUID subject = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-08-03T12:00:00Z");

    private Report theirs(UUID about, Instant at) {
        return Report.filed(UUID.randomUUID().toString(), reporter, "Ayla", about, "Bram",
                "something happened", at);
    }

    @Test
    @DisplayName("an ordinary report is allowed")
    void theOrdinaryCase() {
        assertThat(rule.mayFile(reporter, subject, "he broke my door", List.of(), now).isAllowed())
                .isTrue();
    }

    @Test
    @DisplayName("a report too short to act on is refused")
    void tooShort() {
        assertThat(rule.mayFile(reporter, subject, "no", List.of(), now).refusal())
                .contains(ReportRule.TOO_SHORT);
        assertThat(rule.mayFile(reporter, subject, "   ", List.of(), now).refusal())
                .contains(ReportRule.TOO_SHORT);
        assertThat(rule.mayFile(reporter, subject, null, List.of(), now).refusal())
                .contains(ReportRule.TOO_SHORT);
    }

    @Test
    @DisplayName("nobody reports themselves")
    void notYourself() {
        assertThat(rule.mayFile(reporter, reporter, "I am being annoying", List.of(), now).refusal())
                .contains(ReportRule.NOT_YOURSELF);
    }

    @Test
    @DisplayName("a report with no subject is refused")
    void noSubject() {
        assertThat(rule.mayFile(reporter, null, "somebody did something", List.of(), now).isRefused())
                .isTrue();
    }

    @Test
    @DisplayName("a second report within the cooldown waits")
    void theCooldown() {
        List<Report> already = List.of(theirs(UUID.randomUUID(), now.minusSeconds(30)));

        assertThat(rule.mayFile(reporter, subject, "and now this too", already, now).refusal())
                .contains(ReportRule.TOO_SOON);
    }

    @Test
    @DisplayName("once the cooldown is up they may file again")
    void afterTheCooldown() {
        List<Report> already = List.of(theirs(UUID.randomUUID(), now.minusSeconds(300)));

        assertThat(rule.mayFile(reporter, subject, "and now this too", already, now).isAllowed()).isTrue();
    }

    @Test
    @DisplayName("a second report about the same player is a duplicate, not a queue entry")
    void duplicates() {
        // Two entries about one grief is two moderators walking to the same place. The cooldown alone
        // does not stop it, because the second one usually arrives five minutes later with more detail.
        List<Report> already = List.of(theirs(subject, now.minus(Duration.ofHours(1))));

        assertThat(rule.mayFile(reporter, subject, "he is still at it", already, now).refusal())
                .contains(ReportRule.ALREADY_OPEN);
    }

    @Test
    @DisplayName("a closed report about the same player does not block a new one")
    void aClosedOneDoesNotBlock() {
        // Otherwise a player who was dealt with in March can never be reported again.
        Report closed = theirs(subject, now.minus(Duration.ofDays(30)))
                .resolved(UUID.randomUUID(), "Cyra", "warned", now.minus(Duration.ofDays(30)));

        assertThat(rule.mayFile(reporter, subject, "he is back at it", List.of(closed), now).isAllowed())
                .isTrue();
    }

    @Test
    @DisplayName("too many open at once is refused")
    void tooMany() {
        List<Report> already = List.of(
                theirs(UUID.randomUUID(), now.minus(Duration.ofHours(3))),
                theirs(UUID.randomUUID(), now.minus(Duration.ofHours(2))),
                theirs(UUID.randomUUID(), now.minus(Duration.ofHours(1))));

        assertThat(rule.mayFile(reporter, subject, "yet another one", already, now).refusal())
                .contains(ReportRule.TOO_MANY);
    }

    @Test
    @DisplayName("their closed reports do not count towards the limit")
    void closedOnesDoNotCountTowardsTheLimit() {
        UUID handler = UUID.randomUUID();
        List<Report> already = List.of(
                theirs(UUID.randomUUID(), now.minus(Duration.ofHours(3)))
                        .resolved(handler, "Cyra", "done", now.minus(Duration.ofHours(3))),
                theirs(UUID.randomUUID(), now.minus(Duration.ofHours(2)))
                        .resolved(handler, "Cyra", "done", now.minus(Duration.ofHours(2))),
                theirs(UUID.randomUUID(), now.minus(Duration.ofHours(1)))
                        .rejected(handler, "Cyra", "nothing there", now.minus(Duration.ofHours(1))));

        assertThat(rule.mayFile(reporter, subject, "a genuine one this time", already, now).isAllowed())
                .isTrue();
    }

    @Test
    @DisplayName("a claimed report still counts as one of theirs in hand")
    void claimedOnesStillCount() {
        List<Report> already = List.of(
                theirs(UUID.randomUUID(), now.minus(Duration.ofHours(3))).claimedBy(UUID.randomUUID(), "Cyra"),
                theirs(UUID.randomUUID(), now.minus(Duration.ofHours(2))).claimedBy(UUID.randomUUID(), "Cyra"),
                theirs(UUID.randomUUID(), now.minus(Duration.ofHours(1))).claimedBy(UUID.randomUUID(), "Cyra"));

        assertThat(rule.mayFile(reporter, subject, "yet another one", already, now).refusal())
                .contains(ReportRule.TOO_MANY);
    }

    @Test
    @DisplayName("the console is not rate limited")
    void theConsoleIsNotLimited() {
        assertThat(rule.mayFile(null, subject, "automated detection", List.of(), now).isAllowed()).isTrue();
    }

    @Test
    @DisplayName("a rule with the limits switched off allows everything an ordinary one would")
    void limitsCanBeSwitchedOff() {
        ReportRule open = new ReportRule(Duration.ZERO, 0);
        List<Report> already = List.of(theirs(UUID.randomUUID(), now.minusSeconds(1)));

        assertThat(open.mayFile(reporter, subject, "another one", already, now).isAllowed()).isTrue();
    }

    @Test
    @DisplayName("the rule says what it is about")
    void itDescribesItself() {
        assertThat(rule.describe()).isNotBlank();
    }
}
