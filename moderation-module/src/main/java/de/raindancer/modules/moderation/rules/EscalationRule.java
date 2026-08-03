package de.raindancer.modules.moderation.rules;

import de.raindancer.core.moderation.punishment.Punishment;
import de.raindancer.modules.moderation.model.Reason;
import de.raindancer.modules.moderation.model.Sentence;

import java.util.List;
import java.util.Locale;

/**
 * How long the <em>next</em> one should be.
 *
 * <h2>What this replaces</h2>
 * A moderator deciding on the spot — which is why the same offence got thirty minutes from one person
 * and a permanent ban from another, and why every appeal was arguable. The ladder is the server's policy
 * written down once; this is what reads somebody's record and says which rung they are on.
 *
 * <h2>It only suggests</h2>
 * A moderator can always type a length instead. This is what the screen fills in and what the console
 * line reports as suggested — never something applied behind anybody's back.
 *
 * <h2>How a prior offence is recognised</h2>
 * Core's {@link Punishment} carries the reason as text, because most punishments do not come from this
 * module at all. So a prior offence is one of the same {@link Reason#kind()} whose reason text
 * <em>begins with the reason's label</em> — which is exactly what {@code PunishmentService} writes,
 * either alone or followed by {@code " — "} and whatever the moderator added.
 *
 * <p>The match stops at a word boundary, so {@code Spamming the shop} does not count towards
 * {@code Spam}. Without that, every reason whose label is a prefix of another quietly counts towards it.
 *
 * <h2>Lifted punishments still count</h2>
 * Core never deletes one; lifting a ban adds the lifting to it and leaves the ban. That is what makes a
 * second offence answerable at all, and the ladder agrees with it. Somebody whose first mute was lifted
 * on appeal has still been muted once, and their second is still their second.
 */
public final class EscalationRule implements IModerationRule {

    /**
     * How long this reason should cost somebody with this record, or null when there is no reason.
     *
     * @param history everything Core holds about them, in any order; null is a clean record
     */
    public Sentence suggest(Reason reason, List<Punishment> history) {
        if (reason == null) {
            return null;
        }
        return reason.at(priorOffences(reason, history));
    }

    /** How many times they have done this before. */
    public int priorOffences(Reason reason, List<Punishment> history) {
        if (reason == null || history == null || history.isEmpty()) {
            return 0;
        }
        String label = reason.label().toLowerCase(Locale.ROOT);
        int found = 0;
        for (Punishment past : history) {
            if (past == null || past.kind() != reason.kind()) {
                continue;
            }
            if (namesTheSameReason(past.reason(), label)) {
                found++;
            }
        }
        return found;
    }

    /**
     * Whether a recorded reason is this one.
     *
     * <p>The boundary check is the whole subtlety: a bare {@code startsWith} makes {@code Spamming}
     * count as {@code Spam}, and on a server whose reasons are {@code Grief} and {@code Griefing spawn}
     * that is two ladders climbing each other.
     */
    private static boolean namesTheSameReason(String recorded, String label) {
        if (recorded == null || label.isEmpty()) {
            return false;
        }
        String written = recorded.toLowerCase(Locale.ROOT).trim();
        if (!written.startsWith(label)) {
            return false;
        }
        if (written.length() == label.length()) {
            return true;
        }
        char next = written.charAt(label.length());
        return !Character.isLetterOrDigit(next);
    }

    @Override
    public String describe() {
        return "which rung of a reason's ladder somebody's record puts them on";
    }
}
