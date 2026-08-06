package de.raindancer.modules.hungergames;

import de.raindancer.modules.hungergames.model.GameClock;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.rules.TeamRules;
import de.raindancer.modules.hungergames.service.GameControlService;
import de.raindancer.modules.hungergames.store.GameSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stage preconditions {@link GameControlService} guards — the part of the source
 * {@code GameControlService} that has nothing to do with the arena wave (schematics, tubes, platforms)
 * and can therefore be exercised today, against a real {@link GameSession} and no server. See the
 * class javadoc for why the actual stage work is a collaborator rather than being ported here.
 */
class GameControlServiceTest {

    private GameSession session;
    private GameControlService control;
    private boolean countdownRunning;
    private int initCalls;
    /** The count the build stage was handed — see TheChosenCountIsBuiltTest for why this is watched. */
    private int builtFor;
    private int startupCalls;
    private int startCalls;

    @BeforeEach
    void setUp() {
        session = new GameSession(TeamRules::defaults, new RecordingGameEvents(),
                new InMemorySessionStore(), GameClock.system(), new Random(0));
        control = new GameControlService(session, actor -> countdownRunning,
                (actor, count) -> { initCalls++; builtFor = count; return true; },
                actor -> { startupCalls++; return true; },
                actor -> { startCalls++; return true; });
        control.settings(HungerGamesSettings.DEFAULTS);
    }

    private void advanceTo(GamePhase target) {
        GamePhase[] order = {GamePhase.NOT_INITIALIZED, GamePhase.PREFLIGHT, GamePhase.LOBBY,
                GamePhase.STARTUP, GamePhase.READY, GamePhase.RUNNING, GamePhase.FINISHED};
        for (GamePhase phase : order) {
            if (session.phase() == target) {
                return;
            }
            session.transitionTo(phase);
        }
    }

    @Test
    void initOnlyBeforeAnArenaExistsOrAfterOneHasFinished() {
        assertThat(control.canInit()).isTrue();
        advanceTo(GamePhase.PREFLIGHT);
        assertThat(control.canInit()).isFalse();
        advanceTo(GamePhase.FINISHED);
        assertThat(control.canInit()).isTrue();
    }

    @Test
    void initRefusesAPlayerCountOutsideTheBounds() {
        // One is inside the bounds — see ASoloRoundIsTestableTest. Zero is not.
        assertThat(control.init(UUID.randomUUID(), 0)).isPresent();
        assertThat(control.init(UUID.randomUUID(), 101)).isPresent();
        assertThat(initCalls).isZero();

        assertThat(control.init(UUID.randomUUID(), 8)).isEmpty();
        assertThat(initCalls).isEqualTo(1);
    }

    @Test
    void initRefusesOnceAnArenaAlreadyExists() {
        advanceTo(GamePhase.LOBBY);
        assertThat(control.init(UUID.randomUUID(), 8)).isPresent();
        assertThat(initCalls).isZero();
    }

    @Test
    void startupOnlyFromTheLobby() {
        assertThat(control.startup(UUID.randomUUID())).isPresent();
        advanceTo(GamePhase.LOBBY);
        assertThat(control.startup(UUID.randomUUID())).isEmpty();
        assertThat(startupCalls).isEqualTo(1);
    }

    @Test
    void startOnlyFromReadyAndNeverWhileACountdownIsAlreadyRunning() {
        UUID actor = UUID.randomUUID();
        advanceTo(GamePhase.STARTUP);
        assertThat(control.start(actor)).isPresent(); // not READY yet

        advanceTo(GamePhase.READY);
        countdownRunning = true;
        assertThat(control.start(actor)).isPresent();
        assertThat(startCalls).isZero();

        countdownRunning = false;
        assertThat(control.start(actor)).isEmpty();
        assertThat(startCalls).isEqualTo(1);
    }

    @Test
    void endRoundOnlyWhileRunningAndAlwaysProducesAVerdict() {
        assertThat(control.endRound()).isFalse();
        advanceTo(GamePhase.RUNNING);
        assertThat(control.endRound()).isTrue();
        assertThat(session.phase()).isEqualTo(GamePhase.FINISHED);
        assertThat(session.winner()).isPresent();
    }

    @Test
    void prepareNextRoundResetsTheRoundButKeepsTheRoster() {
        session.whitelistAdd(UUID.randomUUID(), "Katniss");
        advanceTo(GamePhase.RUNNING);
        control.prepareNextRound();
        assertThat(session.phase()).isEqualTo(GamePhase.NOT_INITIALIZED);
        assertThat(session.participants().all()).hasSize(1);
    }
}
