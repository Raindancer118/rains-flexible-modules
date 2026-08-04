package de.raindancer.modules.tpa.rules;

import de.raindancer.modules.tpa.model.TpaPrefs;

import java.util.UUID;

/**
 * Whether one player may ask another.
 *
 * <h2>Named answers rather than a boolean</h2>
 * "They are in another world", "they are not accepting", "you have already asked them" and "wait five
 * seconds" are four different things to tell somebody. A boolean would collapse them into a silent
 * refusal, which is a command people type four more times and then report as broken.
 *
 * <h2>The one answer that is deliberately incomplete</h2>
 * Being blocked and having requests switched off are the <em>same</em> verdict — not two that happen to
 * share wording, which would drift the first time somebody edited one of them. Telling an asker they
 * have been blocked turns a quiet decision into a confrontation, which is precisely what the person who
 * blocked them was avoiding.
 *
 * <h2>The order</h2>
 * Yourself, then the world, then their decision, then whether you have already asked, and the wait
 * <em>last</em>. Asking the wait first means a typo — or somebody who was never going to accept —
 * costs five seconds of waiting for a request that could not have been made.
 */
public final class TpaAskingRule implements ITpaRule {

    /** What, if anything, is wrong with asking. */
    public enum Verdict {

        FINE(null),

        /** There is nowhere to go. Not a permission question, so no bypass gets past it. */
        YOURSELF("tpa.yourself"),

        ANOTHER_WORLD("tpa.another-world"),

        /**
         * They are not accepting — whether from the blanket switch or from having blocked this
         * person. One verdict for both, on purpose; see the class note.
         */
        NOT_ACCEPTING("tpa.not-accepting"),

        /** They have already been asked by this person and have not answered yet. */
        ALREADY_ASKED("tpa.already-asked"),

        /** The wait between one person's requests. */
        TOO_SOON("tpa.too-soon");

        private final String messageKey;

        Verdict(String messageKey) {
            this.messageKey = messageKey;
        }

        /** The wording for this refusal, or null when there is nothing to refuse. */
        public String messageKey() {
            return messageKey;
        }

        public boolean isFine() {
            return this == FINE;
        }
    }

    /**
     * Whether this request may be made.
     *
     * @param theirs         what the person being asked has decided
     * @param reachable      whether the two are in one world, or the server allows crossing one —
     *                       two facts the caller has and the rule does not need to tell apart, since
     *                       the refusal is the same sentence either way
     * @param mayBypassToggle whether the asker holds the node that gets past somebody's decision to be
     *                       left alone — deliberately not covered by {@code operators-bypass}
     * @param alreadyAsked   whether this person has already asked that one
     * @param stillWaiting   whether the wait between requests has not run out
     */
    public Verdict check(UUID from, UUID to, TpaPrefs theirs, boolean reachable,
                         boolean mayBypassToggle, boolean alreadyAsked, boolean stillWaiting) {
        if (from == null || to == null || from.equals(to)) {
            return Verdict.YOURSELF;
        }
        if (!reachable) {
            return Verdict.ANOTHER_WORLD;
        }
        if (!mayBypassToggle && !(theirs == null || theirs.mayBeAskedBy(from))) {
            return Verdict.NOT_ACCEPTING;
        }
        if (alreadyAsked) {
            return Verdict.ALREADY_ASKED;
        }
        if (stillWaiting) {
            return Verdict.TOO_SOON;
        }
        return Verdict.FINE;
    }

    @Override
    public String describe() {
        return "whether one player may ask another";
    }
}
