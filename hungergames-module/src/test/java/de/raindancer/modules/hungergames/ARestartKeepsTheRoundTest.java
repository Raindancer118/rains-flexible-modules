package de.raindancer.modules.hungergames;

import de.raindancer.core.social.team.TeamColour;
import de.raindancer.modules.hungergames.model.GameClock;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.rules.TeamRules;
import de.raindancer.modules.hungergames.store.GameSession;
import de.raindancer.modules.hungergames.store.GameEvents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That a server restarted mid-round comes back to the same round.
 *
 * <h2>The bug this was written for</h2>
 * {@link GameSession} writes itself to disk on every mutation — every whitelist change, every phase move,
 * every elimination — and {@code restore(SessionSnapshot)} existed to read it back. Nothing called it. The
 * store's {@code load()} was never invoked from anywhere in the module, so a session was persisted
 * faithfully and then ignored.
 *
 * <p>What that costs is the whole evening. A Hunger Games round runs for three hours; servers restart,
 * crash, and get restarted deliberately when somebody notices a problem. Coming back, the phase was
 * {@code NOT_INITIALIZED}, the tribute register was empty, the teams were gone and so were the kills — with
 * forty people still connected, standing in an arena the plugin no longer believed existed. There was no
 * error and nothing in the log; the only way back was to re-enter forty names by hand and start again.
 *
 * <p>It was also invisible from the inside. {@code HungerGamesWiring.start()} asked
 * {@code session.phase() == RUNNING} to decide whether to resume the clock — a condition that could never be
 * true, because the thing that would have made it true was the load that never happened. The code reads as
 * though it handles a restart.
 *
 * <h2>The fix, and why it is in the constructor</h2>
 * {@link GameSession} now reads its own store as it is built. That is deliberately not "the wiring calls
 * load() at the right moment": the bug was nobody calling it, and a session that cannot be constructed in a
 * state that has forgotten is a stronger guarantee than a call somebody has to remember. Every test below
 * therefore "restarts" by simply constructing a second session on the same store, with no load call
 * anywhere — which is exactly what a real restart does.
 *
 * <h2>Why this drives the session rather than the module</h2>
 * The session is where the state is and where the restore has to land. A test that booted the module would
 * need a server; this needs a map and a clock, which is the whole reason the session was built free of
 * Bukkit in the first place.
 */
class ARestartKeepsTheRoundTest {

    private static final UUID KATNISS = UUID.randomUUID();
    private static final UUID PEETA = UUID.randomUUID();
    private static final UUID CATO = UUID.randomUUID();

    /** A session on a given store, with nothing else real. */
    private static GameSession sessionOn(InMemorySessionStore store) {
        return new GameSession(TeamRules::defaults, new RecordingGameEvents(), store,
                GameClock.system(), new Random(1));
    }

    /**
     * A round in progress: three tributes, one team, one death, and the clock running.
     *
     * <p>Deliberately not a fresh round. Every one of these is a thing that only exists because somebody did
     * it during the evening, and every one of them was being lost.
     */
    private static GameSession aRoundInProgress(InMemorySessionStore store) {
        GameSession session = sessionOn(store);
        session.whitelistAdd(KATNISS, "Katniss");
        session.whitelistAdd(PEETA, "Peeta");
        session.whitelistAdd(CATO, "Cato");

        var district12 = session.teamCreate("District 12", TeamColour.BLUE).team().orElseThrow().id();
        session.teamAssign(KATNISS, district12, GameEvents.MembershipCause.PLAYER);
        session.teamAssign(PEETA, district12, GameEvents.MembershipCause.PLAYER);

        session.transitionTo(GamePhase.PREFLIGHT);
        session.transitionTo(GamePhase.LOBBY);
        session.transitionTo(GamePhase.STARTUP);
        session.transitionTo(GamePhase.READY);
        session.transitionTo(GamePhase.RUNNING);

        session.eliminate(PEETA, CATO);
        return session;
    }

    @Test
    @DisplayName("the phase survives, so the round is still running")
    void thePhaseComesBack() {
        InMemorySessionStore disk = new InMemorySessionStore();
        aRoundInProgress(disk);

        // The restart, and this is the whole point: a brand new session on the same disk, with nobody
        // calling load(). If the session did not read its own store, this is where the round would vanish.
        GameSession afterRestart = sessionOn(disk);

        assertThat(afterRestart.phase())
                .as("without this the round is NOT_INITIALIZED and forty people are standing in an arena "
                        + "the plugin does not believe exists")
                .isEqualTo(GamePhase.RUNNING);
    }

    @Test
    @DisplayName("the tributes survive, by name as well as by identity")
    void theRegisterComesBack() {
        InMemorySessionStore disk = new InMemorySessionStore();
        aRoundInProgress(disk);

        GameSession afterRestart = sessionOn(disk);

        assertThat(afterRestart.participants().all()).hasSize(3);
        assertThat(afterRestart.isWhitelisted(KATNISS)).isTrue();
        assertThat(afterRestart.participants().nameOf(KATNISS))
                .as("the name is what every announcement and the winner line read from — a UUID in the "
                        + "victory message is worse than no message")
                .contains("Katniss");
    }

    @Test
    @DisplayName("who is out stays out, and who is alive stays alive")
    void theEliminationsComeBack() {
        InMemorySessionStore disk = new InMemorySessionStore();
        aRoundInProgress(disk);

        GameSession afterRestart = sessionOn(disk);

        assertThat(afterRestart.participants().isAlive(PEETA))
                .as("an eliminated tribute who comes back alive has been handed the round")
                .isFalse();
        assertThat(afterRestart.participants().isAlive(KATNISS)).isTrue();
        assertThat(afterRestart.participants().aliveCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("the teams survive, with their members")
    void theTeamsComeBack() {
        InMemorySessionStore disk = new InMemorySessionStore();
        aRoundInProgress(disk);

        GameSession afterRestart = sessionOn(disk);

        assertThat(afterRestart.teams().all())
                .as("teams are organised in the half hour before a round and cannot be reconstructed")
                .hasSize(1);
        assertThat(afterRestart.teams().all().get(0).members()).contains(KATNISS, PEETA);
    }

    @Test
    @DisplayName("the kill tally survives, because it decides who won")
    void theKillsComeBack() {
        InMemorySessionStore disk = new InMemorySessionStore();
        aRoundInProgress(disk);

        GameSession afterRestart = sessionOn(disk);

        assertThat(afterRestart.kills().kills(CATO))
                .as("kills settle a tie and are half of what anybody remembers about an evening")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the clock survives, so the round does not start its three hours again")
    void theClockComesBack() {
        InMemorySessionStore disk = new InMemorySessionStore();
        GameSession before = aRoundInProgress(disk);
        long startedAt = before.runningSinceMillis().orElseThrow();

        GameSession afterRestart = sessionOn(disk);

        assertThat(afterRestart.runningSinceMillis())
                .as("lost, the round restarts its clock and runs for three more hours from whenever the "
                        + "server came back")
                .contains(startedAt);
    }

    @Test
    @DisplayName("nothing is restored when there is nothing saved")
    void afreshServerStaysFresh() {
        InMemorySessionStore emptyDisk = new InMemorySessionStore();

        GameSession session = sessionOn(emptyDisk);

        assertThat(session.phase())
                .as("the ordinary case, and it must not be mistaken for a round to resume")
                .isEqualTo(GamePhase.NOT_INITIALIZED);
        assertThat(session.participants().all()).isEmpty();
    }
}
