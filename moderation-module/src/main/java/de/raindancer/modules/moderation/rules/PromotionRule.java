package de.raindancer.modules.moderation.rules;

import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.modules.moderation.model.StaffRank;

import java.util.Optional;
import java.util.UUID;

/**
 * Who may hand out which rank, and to whom.
 *
 * <h2>The one rule everything else follows from</h2>
 * <b>You may only appoint below yourself.</b> An admin may make somebody a mod, a mod may make somebody
 * a trial mod, and neither may create their own equal — because a rank that can create its own equal can
 * create a second of itself, and then two of those, and the ladder has no top. The owner is outside all
 * of it: they hold the promote node and may hand out anything.
 *
 * <p>The same applies downwards. A mod may take a trial mod off the staff; they may not touch another
 * mod, because a moderation team where any member can remove any other is one bad evening away from
 * having no members.
 *
 * <h2>Why the two directions are separate settings</h2>
 * Because they are not the same trust. A server may be perfectly happy for an admin to appoint mods
 * while reserving the <em>removing</em> of them — appointing somebody who turns out badly is recoverable,
 * and removing somebody in a temper during an argument is what an audit trail gets read about afterwards.
 */
public final class PromotionRule implements IModerationRule {

    /** Refusal keys, which are message keys. */
    public static final String NOT_YOURS = "moderation.rank.not-yours";
    public static final String ONLY_BELOW_YOU = "moderation.rank.only-below-you";
    public static final String NOT_ABOVE_YOU = "moderation.rank.not-above-you";
    public static final String YOURSELF = "moderation.rank.yourself";
    public static final String HANDING_OUT_IS_OFF = "moderation.rank.handing-out-is-off";

    /** Whether somebody holds the owner's promote node — or is the console. */
    @FunctionalInterface
    public interface TheOwner {

        /** @param who null for the console, which is always the owner as far as this is concerned */
        boolean holdsIt(UUID who);
    }

    private final TheOwner owner;
    private final RankOf ranks;
    private final boolean promotingBelowIsAllowed;
    private final boolean demotingBelowIsAllowed;

    /** Somebody's rank, if they have one. */
    @FunctionalInterface
    public interface RankOf {
        Optional<StaffRank> of(UUID who);
    }

    public PromotionRule(TheOwner owner, RankOf ranks, boolean promotingBelowIsAllowed,
                         boolean demotingBelowIsAllowed) {
        this.owner = owner;
        this.ranks = ranks;
        this.promotingBelowIsAllowed = promotingBelowIsAllowed;
        this.demotingBelowIsAllowed = demotingBelowIsAllowed;
    }

    /** The highest rank this person may hand out, if any. */
    public Optional<StaffRank> highestTheyMayGive(UUID actor) {
        if (owner.holdsIt(actor)) {
            return Optional.of(StaffRank.values()[StaffRank.values().length - 1]);
        }
        if (!promotingBelowIsAllowed) {
            return Optional.empty();
        }
        return ranks.of(actor).flatMap(StaffRank::below);
    }

    /** Whether they may hand out ranks at all — for greying a whole page rather than each button. */
    public boolean mayHandOutAnything(UUID actor) {
        return highestTheyMayGive(actor).isPresent();
    }

    /**
     * Whether this person may give this rank to that person.
     *
     * @param subject who is being promoted; their current rank matters as much as the new one
     */
    public Verdict mayPromote(UUID actor, UUID subject, StaffRank wanted) {
        if (owner.holdsIt(actor)) {
            return Verdict.allowed();
        }
        if (actor != null && actor.equals(subject)) {
            // Before anything else, because the answer is the same whatever their rank is and it is the
            // one somebody tries first.
            return Verdict.refused(YOURSELF);
        }
        if (!promotingBelowIsAllowed) {
            return Verdict.refused(HANDING_OUT_IS_OFF);
        }
        Optional<StaffRank> theirs = ranks.of(actor);
        if (theirs.isEmpty()) {
            return Verdict.refused(NOT_YOURS);
        }
        Optional<StaffRank> most = theirs.get().below();
        if (most.isEmpty() || wanted == null || wanted.weight() > most.get().weight()) {
            return Verdict.refused(ONLY_BELOW_YOU,
                    most.map(StaffRank::title).orElse("nothing"));
        }
        // And the person has to currently be below them too, or a mod could "promote" an admin down to
        // trial mod and call it a promotion.
        return actingOnIsAllowed(theirs.get(), subject);
    }

    /** Whether this person may take that person down, or off the staff. */
    public Verdict mayDemote(UUID actor, UUID subject) {
        if (owner.holdsIt(actor)) {
            return Verdict.allowed();
        }
        if (actor != null && actor.equals(subject)) {
            return Verdict.refused(YOURSELF);
        }
        if (!demotingBelowIsAllowed) {
            return Verdict.refused(HANDING_OUT_IS_OFF);
        }
        Optional<StaffRank> theirs = ranks.of(actor);
        if (theirs.isEmpty()) {
            return Verdict.refused(NOT_YOURS);
        }
        return actingOnIsAllowed(theirs.get(), subject);
    }

    /**
     * Whether somebody of this rank may act on this person at all.
     *
     * <p>Only on somebody strictly below them. An equal is the case that matters: a moderation team
     * where any member can remove any other is one bad evening away from having no members.
     */
    private Verdict actingOnIsAllowed(StaffRank mine, UUID subject) {
        Optional<StaffRank> theirs = ranks.of(subject);
        if (theirs.isEmpty()) {
            return Verdict.allowed();       // an ordinary player is below everybody
        }
        if (theirs.get().weight() >= mine.weight()) {
            return Verdict.refused(NOT_ABOVE_YOU, theirs.get().title());
        }
        return Verdict.allowed();
    }

    @Override
    public String describe() {
        return "who may hand out which rank: the owner anything, everybody else the rank below their "
                + "own and only to somebody below them";
    }
}
