package de.raindancer.modules.moderation;

import de.raindancer.core.moderation.punishment.PunishmentKind;
import de.raindancer.modules.moderation.model.Audience;
import de.raindancer.modules.moderation.rules.AnnouncementRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who hears about it.
 *
 * <h2>Why this is a rule rather than four ifs in the service</h2>
 * Because it is a question a screen wants to ask — the punish button says who will hear before it is
 * pressed — and because "everybody was told about a warning" is a bug nobody notices from reading a
 * service that also kicks, audits and writes to two files. Split out, it is nine lines and eleven tests.
 */
class AnnouncementRuleTest {

    private final AnnouncementRule rule = new AnnouncementRule();

    @Test
    @DisplayName("with the defaults, a ban is public and a warning is not")
    void theDefaults() {
        ModerationSettings settings = ModerationSettings.DEFAULTS;

        assertThat(rule.forPunishment(PunishmentKind.BAN, settings)).isEqualTo(Audience.EVERYBODY);
        assertThat(rule.forPunishment(PunishmentKind.MUTE, settings)).isEqualTo(Audience.EVERYBODY);
        assertThat(rule.forPunishment(PunishmentKind.KICK, settings)).isEqualTo(Audience.STAFF);
        assertThat(rule.forPunishment(PunishmentKind.WARNING, settings)).isEqualTo(Audience.STAFF);
    }

    @Test
    @DisplayName("a freeze is staff business, whatever the public setting says")
    void freezingIsQuiet() {
        // Freezing somebody is what a moderator does while they walk over to talk to them. Announcing
        // it to the server tells the person being investigated to log off.
        assertThat(rule.forPunishment(PunishmentKind.FREEZE, ModerationSettings.DEFAULTS))
                .isEqualTo(Audience.STAFF);
    }

    @Test
    @DisplayName("with public announcements off, everything goes to the staff only")
    void publicAnnouncementsCanBeSwitchedOff() {
        ModerationSettings quiet = ModerationSettings.DEFAULTS.withAnnounceToEveryone(false);

        for (PunishmentKind kind : PunishmentKind.values()) {
            assertThat(rule.forPunishment(kind, quiet))
                    .as("%s should be staff-only when public announcements are off", kind)
                    .isEqualTo(Audience.STAFF);
        }
    }

    @Test
    @DisplayName("kicks and warnings can be made public deliberately")
    void theQuietOnesCanBeTurnedUp() {
        ModerationSettings loud = ModerationSettings.DEFAULTS
                .withAnnounceKicks(true).withAnnounceWarnings(true);

        assertThat(rule.forPunishment(PunishmentKind.KICK, loud)).isEqualTo(Audience.EVERYBODY);
        assertThat(rule.forPunishment(PunishmentKind.WARNING, loud)).isEqualTo(Audience.EVERYBODY);
    }

    @Test
    @DisplayName("a lift is announced to whoever heard the punishment")
    void liftsFollowThePunishment() {
        // A ban announced to the server and lifted in silence is how a rumour that somebody was
        // permanently banned outlives the ban by a year.
        assertThat(rule.forLift(PunishmentKind.BAN, ModerationSettings.DEFAULTS))
                .isEqualTo(Audience.EVERYBODY);
        assertThat(rule.forLift(PunishmentKind.FREEZE, ModerationSettings.DEFAULTS))
                .isEqualTo(Audience.STAFF);
    }

    @Test
    @DisplayName("lifts can be kept quiet on their own")
    void liftsCanBeSilenced() {
        ModerationSettings quiet = ModerationSettings.DEFAULTS.withAnnounceLifts(false);

        assertThat(rule.forLift(PunishmentKind.BAN, quiet)).isEqualTo(Audience.STAFF);
    }

    @Test
    @DisplayName("nothing is ever announced to nobody, because staff always want the line")
    void staffAlwaysHearSomething() {
        ModerationSettings quiet = ModerationSettings.DEFAULTS
                .withAnnounceToEveryone(false).withAnnounceLifts(false);

        for (PunishmentKind kind : PunishmentKind.values()) {
            assertThat(rule.forPunishment(kind, quiet)).isNotEqualTo(Audience.NOBODY);
            assertThat(rule.forLift(kind, quiet)).isNotEqualTo(Audience.NOBODY);
        }
    }

    @Test
    @DisplayName("a missing kind or missing settings answers staff rather than throwing")
    void nullsAreSurvived() {
        // This is asked from a render loop. An exception here is a menu that will not open.
        assertThat(rule.forPunishment(null, ModerationSettings.DEFAULTS)).isEqualTo(Audience.STAFF);
        assertThat(rule.forPunishment(PunishmentKind.BAN, null)).isEqualTo(Audience.STAFF);
        assertThat(rule.forLift(null, null)).isEqualTo(Audience.STAFF);
    }

    @Test
    @DisplayName("whether the moderator is named follows the setting, not the audience")
    void namingTheModerator() {
        assertThat(rule.namesTheModerator(Audience.EVERYBODY, ModerationSettings.DEFAULTS)).isFalse();
        assertThat(rule.namesTheModerator(Audience.STAFF, ModerationSettings.DEFAULTS))
                .as("staff always see who did it — that is what makes it answerable")
                .isTrue();
        assertThat(rule.namesTheModerator(Audience.EVERYBODY,
                ModerationSettings.DEFAULTS.withShowModeratorName(true))).isTrue();
    }

    @Test
    @DisplayName("every audience says who it means")
    void everyAudienceDescribesItself() {
        for (Audience audience : Audience.values()) {
            assertThat(audience.describe()).isNotBlank();
        }
    }

    @Test
    @DisplayName("the rule says what it is about")
    void itDescribesItself() {
        assertThat(rule.describe()).isNotBlank();
    }
}
