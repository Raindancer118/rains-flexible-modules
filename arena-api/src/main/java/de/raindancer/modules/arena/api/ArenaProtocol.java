package de.raindancer.modules.arena.api;

/**
 * The words the arena server and the survival server say to each other over RainsCore's server link. The arena server
 * hosts the link, the survival server is its guest; each topic says who asks and who answers.
 */
public final class ArenaProtocol {

    /** Survival → arena: what somebody being sent over wants once they are there. Answer: {@code ok}. */
    public static final String INTENT = "arena.intent";

    /** Either way: one player asks another, who is on the other server. Answer: {@code ok}, or {@code reason}. */
    public static final String INVITE = "arena.invite";

    /** Survival → arena: the answer to an invite from the arena, given on survival. */
    public static final String INVITE_ANSWER = "arena.invite.answer";

    /** Arena → survival: is somebody of this name online over there? Answer: {@code player}, {@code name}. */
    public static final String WHERE = "arena.where";

    /** Arena → survival: please send this player over — an invite they made was accepted in the arena. */
    public static final String SUMMON = "arena.summon";

    /** Arena → survival: this player is coming back, so they are welcomed back rather than announced as new. */
    public static final String RETURNING = "arena.returning";

    /** Arena → survival: take the stake from each of these players and hold it. Answer: {@code ok}, or {@code who}. */
    public static final String STAKE_HOLD = "stake.hold";

    /** Arena → survival: pay the held pot out as given. Answer: {@code ok}. */
    public static final String STAKE_PAY = "stake.pay";

    /** Arena → survival: give every held stake of the fight back. Answer: {@code ok}. */
    public static final String STAKE_REFUND = "stake.refund";

    /** Arena → survival: a copy of what this player carries there, for a fight in their own gear. Answer: {@code items}. */
    public static final String GEAR = "gear.copy";

    /**
     * Arena → survival: this player agreed, in the arena, to a stake of up to {@code stake} and/or to fighting in their own
     * {@code gear}. Survival takes a stake or hands out a copy of gear only for an agreement it has seen.
     */
    public static final String CONSENT = "arena.consent";

    private ArenaProtocol() {
    }
}
