package de.raindancer.modules.moderation.rules;

import de.raindancer.core.moderation.punishment.Punishment;
import de.raindancer.core.moderation.punishment.PunishmentKind;
import de.raindancer.modules.moderation.model.Standing;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Reading a record and answering the question a moderator actually has.
 *
 * <h2>Why "recently" is part of it</h2>
 * A punishment record is permanent, deliberately — that is what makes a second offence answerable. But
 * a kick in 2023 is not a fact about somebody today. Without a window, everybody who has ever been
 * kicked is permanently {@link Standing#WATCHED}, the word stops meaning anything, and the status
 * becomes decoration people learn to ignore.
 *
 * <p>Thirty days: long enough to cover the run of trouble that is actually one incident, short enough
 * that somebody who was told off last month and has been fine since reads as fine.
 */
public final class StandingRule implements IModerationRule {

    /** How far back something still counts as "recent". See the class note for why there is a window. */
    public static final Duration RECENTLY = Duration.ofDays(30);

    private final Duration recently;

    public StandingRule() {
        this(RECENTLY);
    }

    /** With a window of somebody else's choosing. */
    public StandingRule(Duration recently) {
        this.recently = recently == null ? RECENTLY : recently;
    }

    /**
     * Where this record leaves them.
     *
     * @param record everything Core holds about them, in any order; null is a clean sheet
     */
    public Standing of(List<Punishment> record, Instant now) {
        if (record == null || record.isEmpty()) {
            return Standing.GOOD;
        }
        Instant when = now == null ? Instant.now() : now;
        boolean restricted = false;
        boolean recent = false;

        for (Punishment past : record) {
            if (past == null) {
                continue;
            }
            if (past.isActiveAt(when)) {
                // A ban outranks everything: somebody who is banned and also muted is banned, and
                // saying anything else buries the fact that matters.
                if (past.kind() == PunishmentKind.BAN) {
                    return Standing.BANNED;
                }
                restricted = true;
            }
            if (past.givenAt() != null && past.givenAt().isAfter(when.minus(recently))) {
                recent = true;
            }
        }
        if (restricted) {
            return Standing.RESTRICTED;
        }
        return recent ? Standing.WATCHED : Standing.GOOD;
    }

    /**
     * How much is on the record altogether.
     *
     * <p>A separate question from the standing on purpose: good standing with eleven old entries is a
     * different thing from good standing with none, and the sentence should be able to say so without
     * changing what the standing <em>is</em>.
     */
    public int entriesOnRecord(List<Punishment> record) {
        if (record == null) {
            return 0;
        }
        int found = 0;
        for (Punishment past : record) {
            if (past != null) {
                found++;
            }
        }
        return found;
    }

    /**
     * How many warnings they have collected inside the window.
     *
     * <p>What {@code punishments.warns-before-ban} counts. Warnings only, and only recent ones — a
     * threshold that counted a warning from two years ago would ban somebody for a bad week they had
     * in a different summer.
     */
    public int recentWarnings(List<Punishment> record, Instant now) {
        if (record == null || record.isEmpty()) {
            return 0;
        }
        Instant since = (now == null ? Instant.now() : now).minus(recently);
        int found = 0;
        for (Punishment past : record) {
            if (past != null && past.kind() == PunishmentKind.WARNING
                    && past.givenAt() != null && past.givenAt().isAfter(since)) {
                found++;
            }
        }
        return found;
    }

    @Override
    public String describe() {
        return "where a record leaves somebody: banned, restricted, worth an eye, or fine";
    }
}
