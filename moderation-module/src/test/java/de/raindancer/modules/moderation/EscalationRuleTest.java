package de.raindancer.modules.moderation;

import de.raindancer.core.moderation.punishment.Punishment;
import de.raindancer.core.moderation.punishment.PunishmentKind;
import de.raindancer.modules.moderation.model.Reason;
import de.raindancer.modules.moderation.model.Sentence;
import de.raindancer.modules.moderation.model.Severity;
import de.raindancer.modules.moderation.rules.EscalationRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How long the <em>next</em> one should be.
 *
 * <h2>What this replaces</h2>
 * A moderator deciding on the spot, which is why the same offence got thirty minutes from one person
 * and a permanent ban from another, and why every appeal was arguable. The ladder is the server's
 * policy written down once; the rule is what reads a player's record and says which rung they are on.
 *
 * <h2>It only suggests</h2>
 * A moderator can always type a length. This is what the screen fills in and what the console line
 * says was suggested — never something applied behind anybody's back.
 */
class EscalationRuleTest {

    private final EscalationRule rule = new EscalationRule();
    private final UUID player = UUID.randomUUID();
    private final UUID moderator = UUID.randomUUID();
    private final Instant when = Instant.parse("2026-08-03T12:00:00Z");

    private final Reason spam = new Reason("spam", "Spam", PunishmentKind.MUTE, Severity.MINOR,
            List.of(Sentence.of(Duration.ofMinutes(30)),
                    Sentence.of(Duration.ofHours(6)),
                    Sentence.of(Duration.ofDays(3))));

    private Punishment past(PunishmentKind kind, String reason) {
        return new Punishment(UUID.randomUUID().toString(), player, kind, moderator, reason,
                when, null, null, null, null);
    }

    @Test
    @DisplayName("a clean record is the first rung")
    void aCleanRecord() {
        assertThat(rule.priorOffences(spam, List.of())).isZero();
        assertThat(rule.suggest(spam, List.of()).length()).contains(Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("a null history is a clean record rather than an exception")
    void aNullHistory() {
        assertThat(rule.suggest(spam, null).length()).contains(Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("one prior offence for the same reason is the second rung")
    void onePrior() {
        assertThat(rule.suggest(spam, List.of(past(PunishmentKind.MUTE, "Spam"))).length())
                .contains(Duration.ofHours(6));
    }

    @Test
    @DisplayName("a different offence does not count towards this one")
    void otherReasonsDoNotCount() {
        // Otherwise a player warned once for something unrelated starts every ladder on rung two, and
        // the whole scheme stops meaning anything within a month.
        List<Punishment> history = List.of(
                past(PunishmentKind.MUTE, "Advertising"),
                past(PunishmentKind.BAN, "Cheating"));

        assertThat(rule.priorOffences(spam, history)).isZero();
    }

    @Test
    @DisplayName("the same reason handed out as a different kind does not count either")
    void otherKindsDoNotCount() {
        assertThat(rule.priorOffences(spam, List.of(past(PunishmentKind.WARNING, "Spam")))).isZero();
    }

    @Test
    @DisplayName("a reason with detail after it is still the same reason")
    void detailAfterTheLabelStillCounts() {
        // The services write "Spam — in trade chat", so the match has to be on the label at the front
        // rather than on the whole string, or every punishment with a note is a fresh first offence.
        assertThat(rule.priorOffences(spam, List.of(past(PunishmentKind.MUTE, "Spam — in trade chat"))))
                .isOne();
    }

    @Test
    @DisplayName("case does not start a fresh ladder")
    void caseDoesNotMatter() {
        assertThat(rule.priorOffences(spam, List.of(past(PunishmentKind.MUTE, "spam")))).isOne();
    }

    @Test
    @DisplayName("a reason that merely contains the label is not the same reason")
    void aLongerWordIsADifferentReason() {
        // "Spamming" must not count as "Spam" — a prefix match without a boundary would make every
        // reason that starts with the letters of another one count towards it.
        assertThat(rule.priorOffences(spam, List.of(past(PunishmentKind.MUTE, "Spamming the shop")))).isZero();
    }

    @Test
    @DisplayName("a lifted punishment still counts, because nothing is ever deleted")
    void liftedOnesStillCount() {
        // Core never removes a punishment; lifting one adds the lifting to it. That is exactly what
        // makes a second offence answerable, and the ladder has to agree with it.
        Punishment lifted = new Punishment(UUID.randomUUID().toString(), player, PunishmentKind.MUTE,
                moderator, "Spam", when, null, moderator, "appealed", when.plusSeconds(60));

        assertThat(rule.priorOffences(spam, List.of(lifted))).isOne();
    }

    @Test
    @DisplayName("past the top of the ladder it stays at the top")
    void itStaysAtTheTop() {
        List<Punishment> many = List.of(past(PunishmentKind.MUTE, "Spam"),
                past(PunishmentKind.MUTE, "Spam"), past(PunishmentKind.MUTE, "Spam"),
                past(PunishmentKind.MUTE, "Spam"), past(PunishmentKind.MUTE, "Spam"));

        assertThat(rule.priorOffences(spam, many)).isEqualTo(5);
        assertThat(rule.suggest(spam, many).length()).contains(Duration.ofDays(3));
    }

    @Test
    @DisplayName("a history with a null reason is skipped rather than thrown over")
    void nullsInTheHistoryAreSurvived() {
        // Punishment's compact constructor turns a null reason into "no reason given", so this is what
        // an imported vanilla ban actually looks like in the record.
        Punishment imported = new Punishment(null, player, PunishmentKind.BAN, null, null, when,
                null, null, null, null);

        assertThat(rule.priorOffences(spam, List.of(imported))).isZero();
    }

    @Test
    @DisplayName("a null reason has nothing to suggest")
    void aNullReason() {
        assertThat(rule.suggest(null, List.of())).isNull();
        assertThat(rule.priorOffences(null, List.of())).isZero();
    }

    @Test
    @DisplayName("the rule says what it is about")
    void itDescribesItself() {
        assertThat(rule.describe()).isNotBlank();
    }
}
