package de.raindancer.modules.hungergames;

import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.model.SessionSnapshot;
import de.raindancer.core.social.team.TeamColour;
import de.raindancer.core.social.team.TeamId;
import de.raindancer.core.social.team.TeamOutcome;
import de.raindancer.modules.hungergames.model.Winner;
import de.raindancer.modules.hungergames.rules.TeamRules;
import de.raindancer.modules.hungergames.store.GameEvents.MembershipCause;
import de.raindancer.modules.hungergames.store.GameSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GameSession} as the aggregate root: phase transitions, elimination including winner
 * determination, disconnect semantics, and the persistence round-trip.
 */
class GameSessionTest {

    private RecordingGameEvents events;
    private InMemorySessionStore store;
    private GameSession session;

    private final UUID p1 = UUID.randomUUID();
    private final UUID p2 = UUID.randomUUID();
    private final UUID p3 = UUID.randomUUID();
    private final UUID p4 = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        events = new RecordingGameEvents();
        store = new InMemorySessionStore();
        session = new GameSession(TeamRules::defaults, events, store,
                () -> 1_000_000L, new Random(7));
    }

    private void whitelistFour() {
        session.whitelistAdd(p1, "Anna");
        session.whitelistAdd(p2, "Bela");
        session.whitelistAdd(p3, "Cleo");
        session.whitelistAdd(p4, "Divo");
    }

    private void advanceToRunning() {
        session.transitionTo(GamePhase.PREFLIGHT);
        session.transitionTo(GamePhase.LOBBY);
        session.transitionTo(GamePhase.STARTUP);
        session.transitionTo(GamePhase.READY);
        session.transitionTo(GamePhase.RUNNING);
    }

    @Test
    @DisplayName("deleting a team once teams are frozen says so, rather than that there is no such team")
    void aFrozenDeleteIsNotAMissingTeam() {
        // Both refusals reach the session as the same empty Optional from the roster, because delete answers
        // with the team it removed rather than with an outcome. Told "there is no team called red" about a team
        // they are looking at, a gamemaster goes hunting for a bug in the roster; told "teams are locked", they
        // know the round has to end first. This is the only thing keeping those two apart.
        whitelistFour();
        TeamId red = session.teamCreate("Red", TeamColour.RED).team().orElseThrow().id();

        session.transitionTo(GamePhase.PREFLIGHT);
        session.transitionTo(GamePhase.LOBBY);
        assertEquals(TeamOutcome.SUCCESS, session.teamDelete(red),
                "a team can still be deleted in the lobby");

        TeamId blue = session.teamCreate("Blue", TeamColour.BLUE).team().orElseThrow().id();
        // TeamRules.defaults() locks teams from STARTUP onwards.
        session.transitionTo(GamePhase.STARTUP);

        assertEquals(TeamOutcome.FROZEN, session.teamDelete(blue),
                "the team exists and teams are locked, so the refusal is FROZEN");
        assertTrue(session.teams().team(blue).isPresent(),
                "and the team is still there, having not been deleted");

        // The order of the two checks matters: a stale menu naming a team that really has gone must still be
        // told that, rather than being told teams are locked about something that does not exist.
        assertEquals(TeamOutcome.NO_SUCH_TEAM, session.teamDelete(TeamId.fromName("never-existed")),
                "a team that does not exist is still reported as missing, even while frozen");
    }

    @Test
    @DisplayName("Only allowed phase transitions are accepted")
    void phaseTransitions() {
        assertFalse(session.transitionTo(GamePhase.RUNNING));
        assertEquals(GamePhase.NOT_INITIALIZED, session.phase());

        assertTrue(session.transitionTo(GamePhase.PREFLIGHT));
        assertFalse(session.transitionTo(GamePhase.STARTUP)); // PREFLIGHT -> STARTUP skipped
        assertTrue(session.transitionTo(GamePhase.LOBBY));
        assertFalse(session.transitionTo(GamePhase.RUNNING)); // LOBBY -> RUNNING skipped
        assertTrue(session.transitionTo(GamePhase.STARTUP));
        assertTrue(session.transitionTo(GamePhase.READY));
        assertTrue(session.transitionTo(GamePhase.RUNNING));
        assertTrue(events.events.contains("phase:READY->RUNNING"));
    }

    @Test
    @DisplayName("The ordinary run-up /init -> LOBBY -> STARTUP -> READY -> RUNNING goes through")
    void fullHappyPathReachesRunning() {
        whitelistFour();
        assertTrue(session.transitionTo(GamePhase.PREFLIGHT), "init: PREFLIGHT");
        assertTrue(session.transitionTo(GamePhase.LOBBY), "end of preflight: LOBBY");
        assertTrue(session.transitionTo(GamePhase.STARTUP), "/startup: STARTUP");
        assertTrue(session.transitionTo(GamePhase.READY), "arrival: READY");
        assertTrue(session.transitionTo(GamePhase.RUNNING), "/start: RUNNING");
        assertEquals(GamePhase.RUNNING, session.phase());
    }

    @Test
    @DisplayName("STARTUP -> LOBBY is the allowed recovery path when the start-up is aborted")
    void startupCanFallBackToLobby() {
        session.transitionTo(GamePhase.PREFLIGHT);
        session.transitionTo(GamePhase.LOBBY);
        session.transitionTo(GamePhase.STARTUP);

        assertTrue(session.transitionTo(GamePhase.LOBBY), "recovery back to the lobby");
        assertTrue(session.transitionTo(GamePhase.STARTUP), "and trying again");
    }

    @Test
    @DisplayName("Elimination counts kills, fires events and crowns a winner once one is decided")
    void eliminationFlowWithSoloWinner() {
        whitelistFour();
        advanceToRunning();

        assertTrue(session.eliminate(p2, p1));
        assertEquals(1, session.kills().kills(p1));
        assertTrue(session.eliminate(p3, p1));
        assertEquals(2, session.kills().kills(p1));
        assertTrue(session.winner().isEmpty(), "4-2=2 alive -> no winner yet");

        assertTrue(session.eliminate(p4, null));

        Winner winner = session.winner().orElseThrow();
        assertEquals(new Winner.Solo(p1), winner);
        assertEquals(GamePhase.FINISHED, session.phase());
        assertEquals(1, events.winners.size());
    }

    @Test
    @DisplayName("A disconnect does not exist in the model: an offline player stays alive")
    void disconnectedPlayerStaysAlive() {
        whitelistFour();
        advanceToRunning();

        // p4 "disconnects" -- there is no API that counts that as an elimination.
        session.eliminate(p2, p1);
        session.eliminate(p3, p1);

        // p1 and the offline p4 are both still alive -> no winner.
        assertTrue(session.winner().isEmpty());
        assertEquals(2, session.participants().aliveCount());
        assertTrue(session.participants().isAlive(p4));
    }

    @Test
    @DisplayName("A double elimination is idempotent and does not corrupt the count")
    void doubleEliminationIgnored() {
        whitelistFour();
        advanceToRunning();

        assertTrue(session.eliminate(p2, p1));
        assertFalse(session.eliminate(p2, p1));

        assertEquals(1, session.kills().kills(p1));
        assertEquals(3, session.participants().aliveCount());
    }

    @Test
    @DisplayName("Elimination outside RUNNING is ignored")
    void eliminationOnlyDuringRunning() {
        whitelistFour();
        assertFalse(session.eliminate(p1, null));
        assertTrue(session.participants().isAlive(p1));
    }

    @Test
    @DisplayName("A team win: the survivors of the same team win together")
    void teamWinScenario() {
        whitelistFour();
        TeamId red = session.teamCreate("Red", TeamColour.RED).team().orElseThrow().id();
        TeamId blue = session.teamCreate("Blue", TeamColour.BLUE).team().orElseThrow().id();
        session.transitionTo(GamePhase.PREFLIGHT);
        session.transitionTo(GamePhase.LOBBY);
        session.teamAssign(p1, red, MembershipCause.API);
        session.teamAssign(p2, red, MembershipCause.API);
        session.teamAssign(p3, blue, MembershipCause.API);
        session.teamAssign(p4, blue, MembershipCause.API);
        session.transitionTo(GamePhase.STARTUP);
        session.transitionTo(GamePhase.READY);
        session.transitionTo(GamePhase.RUNNING);

        session.eliminate(p3, p1);
        assertTrue(session.winner().isEmpty(), "3 alive across 2 teams -> no winner");

        session.eliminate(p4, p2);
        Winner.Team teamWin = assertInstanceOf(Winner.Team.class, session.winner().orElseThrow());
        assertEquals(red, teamWin.teamId());
        assertTrue(teamWin.members().containsAll(java.util.Set.of(p1, p2)));
    }

    @Test
    @DisplayName("Timeout always ends the round with a result")
    void timeoutDeclaresResult() {
        whitelistFour();
        advanceToRunning();

        session.declareTimeout();

        assertEquals(GamePhase.FINISHED, session.phase());
        assertInstanceOf(Winner.None.class, session.winner().orElseThrow());
    }

    @Test
    @DisplayName("Revive undoes an elimination")
    void reviveRestoresAlive() {
        whitelistFour();
        advanceToRunning();
        session.eliminate(p2, null);

        assertTrue(session.revive(p2));
        assertTrue(session.participants().isAlive(p2));
        assertTrue(events.events.contains("revived:" + p2));
    }

    @Test
    @DisplayName("A whitelist withdrawal also clears team membership")
    void whitelistRemoveClearsTeam() {
        whitelistFour();
        TeamId red = session.teamCreate("Red", TeamColour.RED).team().orElseThrow().id();
        session.transitionTo(GamePhase.PREFLIGHT);
        session.transitionTo(GamePhase.LOBBY);
        session.teamAssign(p1, red, MembershipCause.API);

        assertTrue(session.whitelistRemove(p1));
        assertFalse(session.isWhitelisted(p1));
        assertTrue(session.teams().teamIdOf(p1).isEmpty());
    }

    @Test
    @DisplayName("A session snapshot survives a restart (restore round-trip)")
    void snapshotRestoreRoundtrip() {
        whitelistFour();
        TeamId red = session.teamCreate("Red", TeamColour.RED).team().orElseThrow().id();
        session.transitionTo(GamePhase.PREFLIGHT);
        session.transitionTo(GamePhase.LOBBY);
        session.teamAssign(p1, red, MembershipCause.API);
        session.transitionTo(GamePhase.STARTUP);
        session.transitionTo(GamePhase.READY);
        session.transitionTo(GamePhase.RUNNING);
        session.eliminate(p2, p1);

        SessionSnapshot saved = store.load().orElseThrow();

        GameSession restored = new GameSession(TeamRules::defaults, new RecordingGameEvents(),
                new InMemorySessionStore(), () -> 2_000_000L, new Random(1));
        restored.restore(saved);

        assertEquals(GamePhase.RUNNING, restored.phase());
        assertEquals(3, restored.participants().aliveCount());
        assertFalse(restored.participants().isAlive(p2));
        assertEquals(1, restored.kills().kills(p1));
        assertEquals(red, restored.teams().teamIdOf(p1).orElseThrow());
        assertEquals("Anna", restored.participants().nameOf(p1).orElseThrow());
        assertEquals(1_000_000L, restored.runningSinceMillis().orElseThrow());
    }

    @Test
    @DisplayName("The next round keeps the whitelist and teams, resets round state")
    void nextRoundKeepsRoster() {
        whitelistFour();
        advanceToRunning();
        session.eliminate(p2, p1);
        session.declareTimeout();

        session.resetForNextRound();

        assertEquals(GamePhase.NOT_INITIALIZED, session.phase());
        assertTrue(session.winner().isEmpty());
        assertEquals(4, session.participants().aliveCount());
        assertEquals(0, session.kills().kills(p1));
        assertTrue(session.isWhitelisted(p2));
    }
}
