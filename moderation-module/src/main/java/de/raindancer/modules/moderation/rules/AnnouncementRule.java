package de.raindancer.modules.moderation.rules;

import de.raindancer.core.moderation.punishment.PunishmentKind;
import de.raindancer.modules.moderation.ModerationSettings;
import de.raindancer.modules.moderation.model.Audience;

/**
 * Who hears about it.
 *
 * <h2>Why this is a rule rather than four ifs inside the service</h2>
 * Because it is a question a <em>screen</em> wants to ask: the punish button says who will hear before
 * it is pressed, which is the difference between a moderator knowing they are about to announce
 * somebody's warning to the whole server and finding out afterwards. And because "everybody was told
 * about a warning" is a defect nobody spots by reading a service that also records, kicks, audits and
 * writes to two files.
 *
 * <h2>The two decisions that are not the owner's</h2>
 * A {@link PunishmentKind#FREEZE} is always staff-only. Freezing somebody is what a moderator does
 * while they walk over to talk to them; announcing it to the server tells the person being investigated
 * to log off.
 *
 * <p>And the staff always hear something. A punishment nobody but its author knows about is one nobody
 * can answer for, which is the state that makes a moderation team unmanageable — so {@link Audience}
 * has a {@link Audience#NOBODY} and this never chooses it.
 */
public final class AnnouncementRule implements IModerationRule {

    /** Who should be told that this has just happened to somebody. */
    public Audience forPunishment(PunishmentKind kind, ModerationSettings settings) {
        if (kind == null || settings == null || !settings.announceToEveryone()) {
            return Audience.STAFF;
        }
        return switch (kind) {
            case BAN, MUTE -> Audience.EVERYBODY;
            case KICK -> settings.announceKicks() ? Audience.EVERYBODY : Audience.STAFF;
            case WARNING -> settings.announceWarnings() ? Audience.EVERYBODY : Audience.STAFF;
            // See the class note: never public, whatever the file says.
            case FREEZE -> Audience.STAFF;
        };
    }

    /**
     * Who should be told that it has been lifted.
     *
     * <p>Whoever heard the punishment, unless lifts are switched off on their own. A ban announced to
     * the server and lifted in silence is how a rumour that somebody was permanently banned outlives
     * the ban by a year.
     */
    public Audience forLift(PunishmentKind kind, ModerationSettings settings) {
        if (settings == null || !settings.announceLifts()) {
            return Audience.STAFF;
        }
        return forPunishment(kind, settings);
    }

    /**
     * Whether the line names the moderator.
     *
     * <p>Staff always see who did it — that is what makes it answerable. The public line usually does
     * not, because a named moderator is one who gets followed around by the friends of whoever they
     * banned.
     */
    public boolean namesTheModerator(Audience audience, ModerationSettings settings) {
        if (audience == Audience.STAFF) {
            return true;
        }
        return settings != null && settings.showModeratorName();
    }

    @Override
    public String describe() {
        return "who hears about a punishment, and whether the moderator is named in the line";
    }
}
