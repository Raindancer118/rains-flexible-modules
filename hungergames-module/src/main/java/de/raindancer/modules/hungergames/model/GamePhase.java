package de.raindancer.modules.hungergames.model;

/**
 * Where a round has got to.
 *
 * <p>Seven states, and they only ever go forward. A round that has finished does not go back to running; a
 * reset starts a new round at {@link #NOT_INITIALIZED} rather than rewinding this one. That is what makes the
 * phase safe to write to disk and read back after a restart: there is exactly one way to have arrived at any
 * value, so a session loaded mid-round resumes rather than guessing.
 *
 * <p>Almost everything else in the module asks this enum something. The protection matrix is a table with a
 * row per phase, the border moves differently in each, the lobby box only holds people in two of them, and
 * whether somebody may still pick a team is {@link #isPreGame()}. Which is why the phase is a value and not a
 * pile of booleans: {@code isRunning && !isFinished && hasStarted} is three fields that can disagree, and one
 * of the disagreements lets a tribute change teams after the countdown.
 */
public enum GamePhase {

    /** Nothing has been built yet. {@code /init} has not been run. */
    NOT_INITIALIZED,

    /** The arena is being generated and the world prepared. */
    PREFLIGHT,

    /** Tributes gather in the glass lobby, and teams may still be chosen. */
    LOBBY,

    /** Tributes are taken underground and lifted to their platforms. */
    STARTUP,

    /** Everybody is on a platform, waiting for the countdown. */
    READY,

    /** The round is live. */
    RUNNING,

    /** Somebody has won, or the time ran out. */
    FINISHED;

    /** Whether an arena exists at all — anything past {@link #NOT_INITIALIZED}. */
    public boolean isInitialized() {
        return this != NOT_INITIALIZED;
    }

    /**
     * Whether tributes may still join or change teams.
     *
     * <p>Deliberately generous — it includes {@link #NOT_INITIALIZED}, so somebody can be put on a team before
     * the arena exists at all. Teams are the thing people organise in the half hour before a tournament, and a
     * plugin that refuses to record them until an admin has run {@code /init} makes that half hour useless.
     *
     * <p>A server can lock teams earlier than this through the settings; this is the outer boundary, not the
     * policy.
     */
    public boolean isPreGame() {
        return this == NOT_INITIALIZED || this == PREFLIGHT || this == LOBBY;
    }
}
