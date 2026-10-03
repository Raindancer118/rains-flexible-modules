package de.raindancer.modules.speedrun;

import java.util.Optional;

/**
 * The lobby, for a game mode's own commands ({@code /manhunt start}, {@code resume}, {@code goal
 * remove}) — a mode is handed the run, never the lobby. Static for the same reason {@link SpeedrunModes}
 * is. Every answer is empty when the speedrun module is not running.
 */
public final class SpeedrunControl {

    /** What a start or resume did, and the wording key that says so. */
    public record Answer(SpeedrunLobby.StartOutcome outcome, String messageKey, int players) {
        public boolean started() {
            return outcome == SpeedrunLobby.StartOutcome.STARTED;
        }
    }

    private static volatile SpeedrunLobby lobby;

    private SpeedrunControl() {
    }

    static void ready(SpeedrunLobby live) {
        lobby = live;
    }

    static void stopped() {
        lobby = null;
    }

    public static Optional<SpeedrunLobby.GoalRemoval> removeGoal() {
        SpeedrunLobby live = lobby;
        return live == null ? Optional.empty() : Optional.of(live.removeGoal());
    }

    /** What the start block does: a countdown for everybody in the lobby world — in mode {@code modeId}. */
    public static Optional<Answer> start(String modeId) {
        SpeedrunLobby live = lobby;
        if (live == null) {
            return Optional.empty();
        }
        live.useMode(modeId);
        java.util.Set<java.util.UUID> present = live.presentInLobbyWorld();
        return Optional.of(answer(live, live.beginCountdown(present), present));
    }

    /** {@code /speedrunresume} in mode {@code modeId}: see {@link SpeedrunLobby#resume}. */
    public static Optional<Answer> resume(java.time.Duration already, String modeId) {
        SpeedrunLobby live = lobby;
        if (live == null) {
            return Optional.empty();
        }
        live.useMode(modeId);
        java.util.Set<java.util.UUID> present = live.presentInRunWorlds();
        return Optional.of(answer(live, live.resume(present, already), present));
    }

    static Answer answer(SpeedrunLobby live, SpeedrunLobby.StartOutcome outcome,
                         java.util.Set<java.util.UUID> present) {
        return new Answer(outcome, live.messageFor(outcome, present), present.size());
    }
}
