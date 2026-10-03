package de.raindancer.modules.speedrun;

import java.util.Optional;

/**
 * The lobby's goal, for a game mode's own commands ({@code /manhunt goal remove}) — a mode is handed
 * the run, never the lobby. Static for the same reason {@link SpeedrunModes} is.
 */
public final class SpeedrunGoal {

    private static volatile SpeedrunLobby lobby;

    private SpeedrunGoal() {
    }

    static void ready(SpeedrunLobby live) {
        lobby = live;
    }

    static void stopped() {
        lobby = null;
    }

    /** Empty when the speedrun module is not running. */
    public static Optional<SpeedrunLobby.GoalRemoval> remove() {
        SpeedrunLobby live = lobby;
        return live == null ? Optional.empty() : Optional.of(live.removeGoal());
    }
}
