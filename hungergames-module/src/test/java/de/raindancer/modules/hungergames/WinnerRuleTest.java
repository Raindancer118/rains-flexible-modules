package de.raindancer.modules.hungergames;

import de.raindancer.modules.hungergames.model.Participant;
import de.raindancer.modules.hungergames.model.ParticipantState;
import de.raindancer.core.social.team.TeamId;
import de.raindancer.modules.hungergames.model.Winner;
import de.raindancer.modules.hungergames.rules.WinnerRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Winner determination -- pure, no Bukkit. Covers the required scenarios: four alive, three alive across
 * two teams, several survivors of the same team, and disconnect behaviour (offline counts as alive).
 */
class WinnerRuleTest {

    private static final TeamId RED = new TeamId("red");
    private static final TeamId BLUE = new TeamId("blue");

    private final WinnerRule rule = new WinnerRule();

    private static Participant alive(String name, TeamId team) {
        return new Participant(UUID.randomUUID(), name, ParticipantState.ALIVE, Optional.ofNullable(team));
    }

    private static Participant dead(String name, TeamId team) {
        return new Participant(UUID.randomUUID(), name, ParticipantState.ELIMINATED, Optional.ofNullable(team));
    }

    @Test
    @DisplayName("Four alive players (2 teams) -> no winner yet")
    void fourAliveNoWinner() {
        List<Participant> participants = List.of(
                alive("a", RED), alive("b", RED),
                alive("c", BLUE), alive("d", BLUE));

        assertTrue(rule.resolve(participants).isEmpty());
    }

    @Test
    @DisplayName("Three alive across two teams -> no winner yet")
    void threeAliveTwoTeamsNoWinner() {
        List<Participant> participants = List.of(
                alive("a", RED), alive("b", RED),
                alive("c", BLUE), dead("d", BLUE));

        assertTrue(rule.resolve(participants).isEmpty());
    }

    @Test
    @DisplayName("Several survivors of the same team -> team win")
    void remainingPlayersOfSameTeamWin() {
        List<Participant> participants = List.of(
                alive("a", RED), alive("b", RED),
                dead("c", BLUE), dead("d", BLUE));

        Winner winner = rule.resolve(participants).orElseThrow();
        Winner.Team teamWin = assertInstanceOf(Winner.Team.class, winner);
        assertEquals(RED, teamWin.teamId());
        assertEquals(2, teamWin.members().size());
    }

    @Test
    @DisplayName("A team win includes members who were already eliminated")
    void teamWinIncludesEliminatedMembers() {
        Participant deadRed = dead("a", RED);
        List<Participant> participants = List.of(
                deadRed, alive("b", RED),
                dead("c", BLUE), dead("d", BLUE));

        Winner.Team teamWin = assertInstanceOf(Winner.Team.class,
                rule.resolve(participants).orElseThrow());
        assertTrue(teamWin.members().contains(deadRed.uuid()));
    }

    @Test
    @DisplayName("The last survivor with no team -> solo win")
    void lastTeamlessPlayerWinsSolo() {
        Participant last = alive("a", null);
        List<Participant> participants = List.of(last, dead("b", null), dead("c", null));

        Winner winner = rule.resolve(participants).orElseThrow();
        assertEquals(new Winner.Solo(last.uuid()), winner);
    }

    @Test
    @DisplayName("The last survivor with a team -> that team wins")
    void lastPlayerWithTeamWinsForTeam() {
        List<Participant> participants = List.of(
                alive("a", RED), dead("b", RED),
                dead("c", BLUE), dead("d", BLUE));

        Winner.Team teamWin = assertInstanceOf(Winner.Team.class,
                rule.resolve(participants).orElseThrow());
        assertEquals(RED, teamWin.teamId());
    }

    @Test
    @DisplayName("A teamless survivor next to other survivors -> the round goes on")
    void teamlessAliveBlocksTeamWin() {
        List<Participant> participants = List.of(
                alive("a", RED), alive("b", RED),
                alive("lone", null));

        assertTrue(rule.resolve(participants).isEmpty());
    }

    @Test
    @DisplayName("Nobody alive -> Winner.None")
    void nobodyAliveIsNone() {
        List<Participant> participants = List.of(dead("a", RED), dead("b", BLUE));

        assertInstanceOf(Winner.None.class, rule.resolve(participants).orElseThrow());
    }

    @Test
    @DisplayName("Disconnect changes nothing: ALIVE counts regardless of online status")
    void disconnectedAlivePlayerStillCounts() {
        // A "disconnected" player is simply ALIVE in the model -- there is deliberately no online state.
        // Two survivors from two teams (one of them offline) -> no winner.
        List<Participant> participants = List.of(
                alive("online", RED),
                alive("offline-but-alive", BLUE));

        assertTrue(rule.resolve(participants).isEmpty());
    }

    @Test
    @DisplayName("Timeout without a decision -> Winner.None")
    void timeoutWithoutDecisionIsNone() {
        List<Participant> participants = List.of(alive("a", RED), alive("b", BLUE));

        assertInstanceOf(Winner.None.class, rule.resolveOnTimeout(participants));
    }

    @Test
    @DisplayName("Timeout with one team left still resolves")
    void timeoutWithLastTeamStillResolves() {
        List<Participant> participants = List.of(alive("a", RED), alive("b", RED));

        assertInstanceOf(Winner.Team.class, rule.resolveOnTimeout(participants));
    }
}
