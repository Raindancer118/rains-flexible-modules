package de.raindancer.modules.speedrun;

import de.raindancer.core.ui.profile.PlayerSwitch;

import java.util.UUID;

/**
 * Who somebody joining the server is to the lobby, by {@code late-join} — see {@link SpeedrunLateJoin}.
 * The answer only; {@link SpeedrunLobby#arrive} does what it says.
 */
final class SpeedrunLatecomers {

    /** Marks somebody watching a run because they joined it late — so they stand up once it is over, a restart in between included. */
    static final PlayerSwitch WATCHING = new PlayerSwitch("speedrun", "late-spectator", false);

    /** What the lobby does with somebody who just joined. */
    enum Arrival {
        /** No run under way: the lobby as ever. */
        NO_RUN,
        /** They raced in this run before: they come back to everything they had. */
        RETURNING,
        /** {@code late-join: OFF} — they look on, as they always did. */
        LOOK_ON,
        /** They watch in spectator mode until the run is over. */
        WATCH,
        /** They race from now on. */
        RACE,
        /** A countdown is running: they race from the start, like everybody else. */
        NEXT_START
    }

    private SpeedrunLatecomers() {
    }

    /**
     * @param wasRacing whether they raced in this run before — see {@link #wasRacing}
     * @param notRacing whether they said they are not racing ({@code /speedrun spectate})
     */
    static Arrival decide(SpeedrunLobbyState state, SpeedrunLateJoin rule, boolean wasRacing, boolean notRacing) {
        if (state == SpeedrunLobbyState.READY || state == SpeedrunLobbyState.FINISHED) {
            return Arrival.NO_RUN;
        }
        if (wasRacing) {
            return Arrival.RETURNING;
        }
        SpeedrunLateJoin how = rule == null ? SpeedrunLateJoin.OFF : rule;
        if (how == SpeedrunLateJoin.OFF) {
            return Arrival.LOOK_ON;
        }
        if (how == SpeedrunLateJoin.SPECTATE || notRacing) {
            return Arrival.WATCH;
        }
        return state == SpeedrunLobbyState.COUNTDOWN ? Arrival.NEXT_START : Arrival.RACE;
    }

    /**
     * Whether {@code player} raced in {@code session} at any point: on the roster now, or joined or
     * left it since the start — somebody who left a run and comes back is not a stranger to it.
     */
    static boolean wasRacing(SpeedrunSession session, UUID player) {
        if (session == null || player == null) {
            return false;
        }
        if (session.participants().contains(player)) {
            return true;
        }
        return session.timeline().entries().stream().anyMatch(entry -> player.equals(entry.who())
                && (entry.kind() == SpeedrunTimeline.Kind.LEFT || entry.kind() == SpeedrunTimeline.Kind.JOINED));
    }
}
