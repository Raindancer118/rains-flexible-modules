package de.raindancer.modules.ctf.model;

/** Where a round is. */
public enum Phase {
    /** Players gather in the lobby; not enough yet, or nobody has started it. */
    WAITING,
    /** Enough players: votes are open and the clock runs down. */
    COUNTDOWN,
    /** The map is being built and everybody is brought over. */
    PREPARING,
    /** Flags are up. */
    RUNNING,
    /** Time ran out with a tie: next capture (or kill) decides. */
    OVERTIME,
    /** Over: results, awards, then back to the lobby. */
    OVER;

    public boolean isPlaying() {
        return this == RUNNING || this == OVERTIME;
    }

    public boolean isGathering() {
        return this == WAITING || this == COUNTDOWN;
    }
}
