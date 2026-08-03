package de.raindancer.modules.moderation;

import de.raindancer.core.moderation.punishment.PunishmentKind;
import de.raindancer.modules.moderation.model.Reason;
import de.raindancer.modules.moderation.model.Sentence;
import de.raindancer.modules.moderation.model.Severity;
import de.raindancer.modules.moderation.store.Reasons;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The reasons a moderator picks from, and the ladder each one climbs.
 *
 * <h2>Why presets at all</h2>
 * Because free-text reasons are what make a punishment history unreadable: the same offence written
 * eleven ways cannot be counted, so nothing can tell a first offence from a fifth, and every length is
 * therefore somebody's guess on the day. A preset is a reason with an <em>identity</em>, and identity is
 * what makes {@code EscalationRule} possible at all.
 *
 * <p>Free text is still allowed — {@code /ban <player> <length> <whatever>} — it simply does not climb a
 * ladder, and that trade is deliberate.
 */
class ReasonsTest {

    @Nested
    @DisplayName("one reason")
    class OneReason {

        @Test
        @DisplayName("a ladder is climbed one rung per prior offence")
        void theLadderIsClimbed() {
            Reason spam = new Reason("spam", "Spam", PunishmentKind.MUTE, Severity.MINOR,
                    List.of(Sentence.of(Duration.ofMinutes(30)),
                            Sentence.of(Duration.ofHours(6)),
                            Sentence.of(Duration.ofDays(3))));

            assertThat(spam.at(0).length()).contains(Duration.ofMinutes(30));
            assertThat(spam.at(1).length()).contains(Duration.ofHours(6));
            assertThat(spam.at(2).length()).contains(Duration.ofDays(3));
        }

        @Test
        @DisplayName("past the top rung it stays on the top rung")
        void itStopsAtTheTop() {
            // Somebody's ninth offence gets whatever the last rung says — usually permanent. An index
            // out of bounds here would be an exception thrown at the moment a moderator clicks a button.
            Reason spam = new Reason("spam", "Spam", PunishmentKind.MUTE, Severity.MINOR,
                    List.of(Sentence.of(Duration.ofMinutes(30)), Sentence.forEver()));

            assertThat(spam.at(1).isPermanent()).isTrue();
            assertThat(spam.at(2).isPermanent()).isTrue();
            assertThat(spam.at(99).isPermanent()).isTrue();
        }

        @Test
        @DisplayName("a nonsensical count lands on the first rung rather than throwing")
        void negativeCountsAreTheFirstRung() {
            Reason spam = new Reason("spam", "Spam", PunishmentKind.MUTE, Severity.MINOR,
                    List.of(Sentence.of(Duration.ofMinutes(30)), Sentence.forEver()));

            assertThat(spam.at(-1).length()).contains(Duration.ofMinutes(30));
        }

        @Test
        @DisplayName("a reason without a ladder is refused")
        void aReasonNeedsALadder() {
            assertThatThrownBy(() -> new Reason("spam", "Spam", PunishmentKind.MUTE, Severity.MINOR,
                    List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a reason without an id or a label is refused")
        void aReasonNeedsAName() {
            List<Sentence> ladder = List.of(Sentence.forEver());

            assertThatThrownBy(() -> new Reason("  ", "Spam", PunishmentKind.MUTE, Severity.MINOR, ladder))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Reason("spam", "", PunishmentKind.MUTE, Severity.MINOR, ladder))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("the id is lower case, so two spellings are one reason")
        void theIdIsNormalised() {
            Reason shouting = new Reason("  SPAM  ", "Spam", PunishmentKind.MUTE, Severity.MINOR,
                    List.of(Sentence.forEver()));

            assertThat(shouting.id()).isEqualTo("spam");
        }

        @Test
        @DisplayName("a one-rung reason does not pretend to escalate")
        void oneRungDoesNotEscalate() {
            Reason once = new Reason("cheating", "Cheating", PunishmentKind.BAN, Severity.SEVERE,
                    List.of(Sentence.forEver()));

            assertThat(once.escalates()).isFalse();
        }
    }

    @Nested
    @DisplayName("the built-in catalogue")
    class Catalogue {

        private final Reasons reasons = Reasons.builtIn();

        @Test
        @DisplayName("it is not empty, so a scan of it cannot pass vacuously")
        void itHasReasons() {
            assertThat(reasons.all()).isNotEmpty();
            assertThat(reasons.size()).isEqualTo(reasons.all().size());
        }

        @Test
        @DisplayName("every kind a moderator hands out has at least one reason to hand it out for")
        void everyKindIsCovered() {
            for (PunishmentKind kind : List.of(PunishmentKind.BAN, PunishmentKind.MUTE,
                    PunishmentKind.KICK, PunishmentKind.WARNING, PunishmentKind.FREEZE)) {
                assertThat(reasons.forKind(kind))
                        .as("no preset reason produces a %s, so that button opens an empty page", kind)
                        .isNotEmpty();
            }
        }

        @Test
        @DisplayName("no two reasons share an id")
        void idsAreUnique() {
            assertThat(reasons.all().stream().map(Reason::id).distinct().count())
                    .as("two reasons with one id means the escalation ladder counts the wrong offences")
                    .isEqualTo(reasons.size());
        }

        @Test
        @DisplayName("a reason can be found by its id, whatever case it is typed in")
        void lookupIsCaseInsensitive() {
            String anyId = reasons.all().getFirst().id();

            assertThat(reasons.byId(anyId)).isPresent();
            assertThat(reasons.byId(anyId.toUpperCase(java.util.Locale.ROOT))).isPresent();
            assertThat(reasons.byId("no-such-reason")).isEmpty();
            assertThat(reasons.byId(null)).isEmpty();
        }

        @Test
        @DisplayName("a kick or a warning never carries a length")
        void momentaryPunishmentsHaveNoLength() {
            // A kick is over the moment it lands and a warning never stopped anybody doing anything.
            // A ladder of durations on either would be a length written into the record that means
            // nothing, and a menu offering to choose it.
            for (Reason reason : reasons.all()) {
                if (!reason.kind().isLasting()) {
                    assertThat(reason.ladder())
                            .as("%s is a %s and should have exactly one rung", reason.id(), reason.kind())
                            .hasSize(1);
                }
            }
        }

        @Test
        @DisplayName("every ladder gets longer as it is climbed")
        void laddersOnlyGetLonger() {
            for (Reason reason : reasons.all()) {
                List<Sentence> ladder = reason.ladder();
                for (int rung = 1; rung < ladder.size(); rung++) {
                    Sentence below = ladder.get(rung - 1);
                    Sentence here = ladder.get(rung);
                    if (below.isPermanent()) {
                        // Nothing is longer than for ever, so a rung above one is unreachable wording.
                        throw new AssertionError(reason.id() + " has a rung above a permanent one, "
                                + "which nothing can ever climb to");
                    }
                    if (here.isPermanent()) {
                        continue;
                    }
                    assertThat(here.length().orElseThrow())
                            .as("%s: rung %d is not longer than the one below it", reason.id(), rung)
                            .isGreaterThan(below.length().orElseThrow());
                }
            }
        }

        @Test
        @DisplayName("the severest reasons end permanently, or the ladder never bites")
        void severeReasonsEndPermanently() {
            List<Reason> severe = reasons.all().stream()
                    .filter(reason -> reason.severity() == Severity.SEVERE)
                    .filter(reason -> reason.kind().isLasting())
                    .toList();

            assertThat(severe).isNotEmpty();
            assertThat(severe).allSatisfy(reason ->
                    assertThat(reason.ladder().getLast().isPermanent())
                            .as("%s is severe and its top rung is not permanent", reason.id())
                            .isTrue());
        }
    }
}
