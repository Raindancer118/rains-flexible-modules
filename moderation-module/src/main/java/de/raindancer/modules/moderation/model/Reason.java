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

    /** The id every typed reason carries, so a screen can tell one from a preset. */
    public static final String TYPED_ID = "typed-by-hand";

    /** As much typed text as is kept. Long enough for a sentence, short enough for a lore line. */
    public static final int LONGEST_TYPED = 120;

    /**
     * A reason somebody typed, for the cases the catalogue does not cover.
     *
     * <p>Counts towards nothing, deliberately. A typed reason cannot be matched against a previous
     * one — "griefing", "Griefing " and "grief" are not obviously the same offence — and guessing that
     * they are would make the ladder climb on a coincidence, in the direction that punishes harder.
     * The record already says "typed by hand — counts towards nothing" for exactly this.
     *
     * @param kind what handing it out does
     * @param text what they typed; blank is refused, over-long is cut rather than thrown away
     */
    public static Reason typedByHand(PunishmentKind kind, String text) {
        if (text == null || text.isBlank()) {
            // "no reason given" is a decision somebody makes on purpose. An empty string is a
            // mistake, and a punishment nobody can explain later is the thing appeals founder on.
            throw new IllegalArgumentException("a typed reason needs some text");
        }
        String trimmed = text.trim();
        if (trimmed.length() > LONGEST_TYPED) {
            // Cut rather than refused: refusing throws away what they typed after they typed it, and
            // the beginning is the part that says what happened.
            trimmed = trimmed.substring(0, LONGEST_TYPED).trim();
        }
        return new Reason(TYPED_ID, trimmed, kind, Severity.MINOR, List.of(Sentence.forEver()));
    }

    /** Whether this came from somebody's keyboard rather than the catalogue. */
    public boolean isTypedByHand() {
        return TYPED_ID.equals(id);
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
