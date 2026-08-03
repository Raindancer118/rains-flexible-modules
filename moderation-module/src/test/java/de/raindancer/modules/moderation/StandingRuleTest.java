package de.raindancer.modules.moderation;

import de.raindancer.core.moderation.punishment.Punishment;
import de.raindancer.core.moderation.punishment.PunishmentKind;
import de.raindancer.modules.moderation.model.Standing;
import de.raindancer.modules.moderation.rules.StandingRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where somebody stands, in one word.
 *
 * <h2>Why this is worth a type</h2>
 * "Is this player all right?" is the question a moderator actually has, and answering it from a raw
 * record means reading eleven entries and doing the arithmetic on every one — is this still in force,
 * was it lifted, was it three years ago. Done by eye, at speed, that arithmetic is wrong often enough
 * that people stop checking and just ask in staff chat.
 *
 * <h2>Why "recently" is part of it</h2>
 * A punishment record is permanent, deliberately — but a kick in 2023 is not a fact about somebody
 * today. Without a window, everybody who has ever been kicked is permanently "watched", which makes the
 * word mean nothing and the whole status decorative.
 */
class StandingRuleTest {

    private final StandingRule rule = new StandingRule();
    private final UUID player = UUID.randomUUID();
    private final UUID moderator = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-08-03T12:00:00Z");

    /** A punishment handed out this long ago, still in force unless it is given an end. */
    private Punishment past(PunishmentKind kind, Duration ago) {
        return new Punishment(UUID.randomUUID().toString(), player, kind, moderator, "something",
                now.minus(ago), null, null, null, null);
    }

    /** One that has already expired. */
    private Punishment expired(PunishmentKind kind, Duration ago) {
        return new Punishment(UUID.randomUUID().toString(), player, kind, moderator, "something",
                now.minus(ago), now.minus(ago).plus(Duration.ofHours(1)), null, null, null);
    }

    @Nested
    @DisplayName("nothing against them")
    class Clean {

        @Test
        @DisplayName("an empty record is good standing")
        void anEmptyRecord() {
            assertThat(rule.of(List.of(), now)).isEqualTo(Standing.GOOD);
        }

        @Test
        @DisplayName("a null record is good standing rather than an exception")
        void aNullRecord() {
            // Asked from a render loop and from a command. Throwing here is a menu that will not open.
            assertThat(rule.of(null, now)).isEqualTo(Standing.GOOD);
        }

        @Test
        @DisplayName("something long ago and long over is good standing again")
        void oldTroubleIsForgiven() {
            assertThat(rule.of(List.of(expired(PunishmentKind.MUTE, Duration.ofDays(400))), now))
                    .isEqualTo(Standing.GOOD);
        }
    }

    @Nested
    @DisplayName("something in force")
    class InForce {

        @Test
        @DisplayName("a ban in force outranks everything else")
        void banned() {
            assertThat(rule.of(List.of(past(PunishmentKind.MUTE, Duration.ofHours(1)),
                    past(PunishmentKind.BAN, Duration.ofHours(1))), now))
                    .isEqualTo(Standing.BANNED);
        }

        @Test
        @DisplayName("a mute in force is a restriction")
        void muted() {
            assertThat(rule.of(List.of(past(PunishmentKind.MUTE, Duration.ofHours(1))), now))
                    .isEqualTo(Standing.RESTRICTED);
        }

        @Test
        @DisplayName("a freeze in force is a restriction too")
        void frozen() {
            assertThat(rule.of(List.of(past(PunishmentKind.FREEZE, Duration.ofMinutes(5))), now))
                    .isEqualTo(Standing.RESTRICTED);
        }

        @Test
        @DisplayName("a ban that has expired no longer counts as one")
        void anExpiredBan() {
            assertThat(rule.of(List.of(expired(PunishmentKind.BAN, Duration.ofDays(200))), now))
                    .isEqualTo(Standing.GOOD);
        }

        @Test
        @DisplayName("a lifted ban does not leave somebody banned")
        void aLiftedBan() {
            Punishment lifted = new Punishment(UUID.randomUUID().toString(), player,
                    PunishmentKind.BAN, moderator, "a mistake", now.minus(Duration.ofDays(2)), null,
                    moderator, "wrong person", now.minus(Duration.ofDays(2)));

            // Lifted, so not banned — but it happened two days ago, so it is not "nothing" either.
            assertThat(rule.of(List.of(lifted), now)).isEqualTo(Standing.WATCHED);
        }
    }

    @Nested
    @DisplayName("recent trouble, nothing in force")
    class Recent {

        @Test
        @DisplayName("a warning last week is watched")
        void aRecentWarning() {
            assertThat(rule.of(List.of(past(PunishmentKind.WARNING, Duration.ofDays(7))), now))
                    .isEqualTo(Standing.WATCHED);
        }

        @Test
        @DisplayName("a kick yesterday is watched")
        void aRecentKick() {
            assertThat(rule.of(List.of(past(PunishmentKind.KICK, Duration.ofDays(1))), now))
                    .isEqualTo(Standing.WATCHED);
        }

        @Test
        @DisplayName("the window has an edge, and it is where it says it is")
        void theEdgeOfTheWindow() {
            Duration justInside = StandingRule.RECENTLY.minus(Duration.ofHours(1));
            Duration justOutside = StandingRule.RECENTLY.plus(Duration.ofHours(1));

            assertThat(rule.of(List.of(past(PunishmentKind.WARNING, justInside)), now))
                    .isEqualTo(Standing.WATCHED);
            assertThat(rule.of(List.of(past(PunishmentKind.WARNING, justOutside)), now))
                    .isEqualTo(Standing.GOOD);
        }
    }

    @Nested
    @DisplayName("what it says")
    class Saying {

        @Test
        @DisplayName("every standing reads as a sentence about a person")
        void everyStandingIsASentence() {
            for (Standing standing : Standing.values()) {
                assertThat(standing.describe())
                        .as("%s", standing)
                        .isNotBlank();
                assertThat(standing.colour()).isNotBlank();
                assertThat(standing.icon()).isNotNull();
            }
        }

        @Test
        @DisplayName("good standing says so in the words somebody would use")
        void goodStandingReadsWell() {
            assertThat(Standing.GOOD.describe()).isEqualTo("in good standing");
        }

        @Test
        @DisplayName("the ranks are ordered worst last, so two can be compared")
        void theyAreOrdered() {
            assertThat(Standing.GOOD.weight()).isLessThan(Standing.WATCHED.weight());
            assertThat(Standing.WATCHED.weight()).isLessThan(Standing.RESTRICTED.weight());
            assertThat(Standing.RESTRICTED.weight()).isLessThan(Standing.BANNED.weight());
        }
    }

    @Test
    @DisplayName("how much is on the record is counted separately from the standing")
    void theCountIsItsOwnQuestion() {
        // Good standing with eleven old entries is a different thing from good standing with none,
        // and the sentence should be able to say so without changing what the standing *is*.
        List<Punishment> record = List.of(expired(PunishmentKind.MUTE, Duration.ofDays(400)),
                expired(PunishmentKind.KICK, Duration.ofDays(300)));

        assertThat(rule.of(record, now)).isEqualTo(Standing.GOOD);
        assertThat(rule.entriesOnRecord(record)).isEqualTo(2);
        assertThat(rule.entriesOnRecord(null)).isZero();
    }

    @Test
    @DisplayName("the rule says what it is about")
    void itDescribesItself() {
        assertThat(rule.describe()).isNotBlank();
    }
}
