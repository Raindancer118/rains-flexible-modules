package de.raindancer.modules.hungergames.model;

/**
 * Whether a tribute is still in the game.
 *
 * <h2>Two values, and the reason there is no third</h2>
 * Being online is <b>not</b> a state. A tribute who disconnects stays {@link #ALIVE} until something
 * eliminates them, and that is the invariant the whole round rests on.
 *
 * <p>The alternative — a {@code DISCONNECTED} state — reads as more accurate and is a trap. Somebody whose
 * connection drops in the last four minutes of a tournament comes back to find the round over and somebody
 * else declared the winner, because the moment they went offline the count of who was left changed. That
 * happened, and the fix is this enum having two values rather than three: whoever is watching connections may
 * <em>choose</em> to eliminate a tribute who has been gone too long, and that is a decision with a setting
 * behind it, taken in one place, rather than a state change that silently ends rounds.
 */
public enum ParticipantState {

    /** Still in the game, whether or not they are connected right now. */
    ALIVE,

    /** Out — killed, or taken out by hand. */
    ELIMINATED
}
