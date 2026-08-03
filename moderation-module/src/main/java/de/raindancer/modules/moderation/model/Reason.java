package de.raindancer.modules.moderation.model;

import de.raindancer.core.moderation.punishment.PunishmentKind;

import java.util.List;
import java.util.Locale;

/**
 * A reason a moderator picks, and the ladder it climbs.
 *
 * <h2>Why a preset rather than free text</h2>
 * Free text is what makes a punishment history unreadable. The same offence written eleven ways cannot
 * be counted, so nothing can tell a first offence from a fifth, and every length is therefore somebody's
 * guess on the day — which is also what makes every appeal arguable. A preset is a reason with an
 * <em>identity</em>, and identity is what makes {@code EscalationRule} possible at all.
 *
 * <p>Free text is still allowed. {@code /ban <player> <length> <whatever>} works and always will; it
 * simply does not climb a ladder, and that trade is deliberate.
 *
 * <h2>The ladder</h2>
 * One rung per prior offence <em>of this reason</em>. Past the top it stays on the top, so a ninth
 * offence gets whatever the last rung says — which for anything {@link Severity#SEVERE} is permanent.
 *
 * @param id       how this reason is recognised again, lower case; the key the ladder counts by
 * @param label    what a moderator sees, and what is written into the punishment's reason
 * @param kind     what handing it out actually does
 * @param severity how bad, for ordering and colouring
 * @param ladder   first offence first; never empty
 */
public record Reason(String id, String label, PunishmentKind kind, Severity severity,
                     List<Sentence> ladder) {

    public Reason {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("a reason needs an id");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("a reason needs a label somebody can read");
        }
        if (kind == null) {
            throw new IllegalArgumentException("a reason needs to say what handing it out does");
        }
        if (severity == null) {
            throw new IllegalArgumentException("a reason needs a severity");
        }
        if (ladder == null || ladder.isEmpty()) {
            throw new IllegalArgumentException(
                    "a reason with no rungs is a reason nothing can hand out: " + id);
        }
        // Lower case, so "Spam" typed in the console and "spam" clicked in a menu are one reason and
        // the ladder counts them together.
        id = id.trim().toLowerCase(Locale.ROOT);
        label = label.trim();
        ladder = List.copyOf(ladder);
    }

    /**
     * What this offence costs somebody who has done it this many times before.
     *
     * <p>Clamped at both ends. Out of bounds here would be an exception thrown at the moment a
     * moderator clicks a button, and neither end is worth that: below zero is a miscount, above the
     * top is somebody's ninth offence.
     */
    public Sentence at(int priorOffences) {
        int rung = Math.max(0, Math.min(ladder.size() - 1, priorOffences));
        return ladder.get(rung);
    }

    /** What a first offence costs. */
    public Sentence first() {
        return ladder.getFirst();
    }

    /** Whether it gets worse with repetition, for the lore line that says so. */
    public boolean escalates() {
        return ladder.size() > 1;
    }
}
