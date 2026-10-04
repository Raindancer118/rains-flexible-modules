package de.raindancer.modules.speedrun;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who somebody joining the server is to the lobby — every combination of the lobby's state, the
 * {@code late-join} rule, whether they raced in this run before, and whether they said they are not
 * racing ({@code /speedrun spectate}).
 */
class SpeedrunLatecomersTest {

    private static SpeedrunLatecomers.Arrival expected(SpeedrunLobbyState state, SpeedrunLateJoin rule,
                                                       boolean wasRacing, boolean notRacing) {
        // Between runs there is nothing to join late; a ready lobby hands out its items as ever.
        if (state == SpeedrunLobbyState.READY || state == SpeedrunLobbyState.FINISHED) {
            return SpeedrunLatecomers.Arrival.NO_RUN;
        }
        // Somebody who raced in this run is no latecomer: they keep everything, whatever the rule.
        if (wasRacing) {
            return SpeedrunLatecomers.Arrival.RETURNING;
        }
        if (rule == SpeedrunLateJoin.OFF) {
            return SpeedrunLatecomers.Arrival.LOOK_ON;
        }
        // Somebody who said they are not racing is never pulled into one — at most they watch.
        if (rule == SpeedrunLateJoin.SPECTATE || notRacing) {
            return SpeedrunLatecomers.Arrival.WATCH;
        }
        // RACE: in a countdown they simply race from the start; once the clock runs, from now.
        return state == SpeedrunLobbyState.COUNTDOWN ? SpeedrunLatecomers.Arrival.NEXT_START
                : SpeedrunLatecomers.Arrival.RACE;
    }

    @Test
    @DisplayName("every state, rule, earlier participation and stated wish has exactly one answer")
    void everyCombination() {
        List<String> checked = new ArrayList<>();
        for (SpeedrunLobbyState state : SpeedrunLobbyState.values()) {
            for (SpeedrunLateJoin rule : SpeedrunLateJoin.values()) {
                for (boolean wasRacing : new boolean[]{false, true}) {
                    for (boolean notRacing : new boolean[]{false, true}) {
                        assertThat(SpeedrunLatecomers.decide(state, rule, wasRacing, notRacing))
                                .as("%s, %s, raced before %s, not racing %s", state, rule, wasRacing, notRacing)
                                .isEqualTo(expected(state, rule, wasRacing, notRacing));
                        checked.add(state + "/" + rule + "/" + wasRacing + "/" + notRacing);
                    }
                }
            }
        }
        assertThat(checked).hasSize(SpeedrunLobbyState.values().length * SpeedrunLateJoin.values().length * 4);
    }

    @Test
    @DisplayName("the defaults change nothing: late-join is OFF, and a latecomer only looks on")
    void offByDefault() {
        assertThat(SpeedrunSettings.DEFAULTS.lateJoinOrOff()).isEqualTo(SpeedrunLateJoin.OFF);
        assertThat(SpeedrunLatecomers.decide(SpeedrunLobbyState.RUNNING, SpeedrunSettings.DEFAULTS.lateJoinOrOff(),
                false, false)).isEqualTo(SpeedrunLatecomers.Arrival.LOOK_ON);
    }

    @Test
    @DisplayName("somebody who raced in this run — still on the roster, or once joined or left it — was racing")
    void wasRacing() {
        java.util.UUID anna = java.util.UUID.randomUUID();
        java.util.UUID ben = java.util.UUID.randomUUID();
        java.util.UUID cleo = java.util.UUID.randomUUID();
        java.util.UUID dan = java.util.UUID.randomUUID();
        SpeedrunSession session = new SpeedrunSession(new java.util.HashSet<>(java.util.Set.of(anna, ben)));
        session.start();
        session.addParticipant(cleo);
        session.removeParticipant(ben);

        assertThat(SpeedrunLatecomers.wasRacing(session, anna)).isTrue();
        assertThat(SpeedrunLatecomers.wasRacing(session, ben)).as("left").isTrue();
        assertThat(SpeedrunLatecomers.wasRacing(session, cleo)).as("joined").isTrue();
        assertThat(SpeedrunLatecomers.wasRacing(session, dan)).isFalse();
        assertThat(SpeedrunLatecomers.wasRacing(null, dan)).isFalse();
    }
}
