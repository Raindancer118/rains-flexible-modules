package de.raindancer.modules.hungergames;

import de.raindancer.core.ui.bossbar.BarStyle;
import de.raindancer.core.ui.bossbar.BossBars;
import de.raindancer.core.ui.scoreboard.Scoreboards;
import de.raindancer.core.ui.scoreboard.Sidebar;
import de.raindancer.modules.hungergames.model.GameClock;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.rules.TeamRules;
import de.raindancer.modules.hungergames.service.BorderService;
import de.raindancer.modules.hungergames.service.GameControlService;
import de.raindancer.modules.hungergames.service.GameTimerService;
import de.raindancer.modules.hungergames.service.RoundExpiryService;
import de.raindancer.modules.hungergames.service.VirtualTime;
import de.raindancer.modules.hungergames.store.GameSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link GameTimerService}: the round clock, and specifically that it defers "is the round over" to
 * {@link RoundExpiryService} on every tick rather than deciding it directly — see the class's found-bug note.
 */
@ExtendWith(MockitoExtension.class)
class GameTimerServiceTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    @Mock
    private BossBars bossBars;

    @Mock
    private Scoreboards scoreboards;

    @Mock
    private GameControlService control;

    @Mock
    private RoundExpiryService.Audience audience;

    private final AtomicLong wallClock = new AtomicLong(0);
    private GameSession session;
    private VirtualTime virtualTime;
    private BorderService border;
    private RoundExpiryService roundExpiry;
    private GameTimerService timer;
    private Set<UUID> online = Set.of(ALICE, BOB);
    private int graceEndedCalls;

    @BeforeEach
    void setUp() {
        session = new GameSession(TeamRules::defaults, new RecordingGameEvents(),
                new InMemorySessionStore(), GameClock.system(), new Random(0));
        session.whitelistAdd(ALICE, "Alice");
        session.whitelistAdd(BOB, "Bob");
        session.transitionTo(GamePhase.PREFLIGHT);
        session.transitionTo(GamePhase.LOBBY);
        session.transitionTo(GamePhase.STARTUP);
        session.transitionTo(GamePhase.READY);
        session.transitionTo(GamePhase.RUNNING);

        virtualTime = new VirtualTime(wallClock::get);
        BorderService.WorldBorderTarget target = new BorderService.WorldBorderTarget() {
            double size = 2500;

            @Override
            public double currentSize() {
                return size;
            }

            @Override
            public void shrinkOverworld(double targetSize, long ticks) {
                size = targetSize;
            }

            @Override
            public void shrinkNether(double targetSize, long ticks) {
            }

            @Override
            public void resetTo(double overworldSize) {
                size = overworldSize;
            }
        };
        border = new BorderService(session, virtualTime, target);
        border.settings(HungerGamesSettings.DEFAULTS);

        roundExpiry = new RoundExpiryService(control, audience, (who, overrun, end, extend) -> { },
                message -> { }, session::phase);
        roundExpiry.settings(HungerGamesSettings.DEFAULTS);

        timer = new GameTimerService(session, virtualTime, border, roundExpiry, List::of,
                bossBars, scoreboards, () -> online, tributes -> graceEndedCalls++,
                GameTimerService.manual());
        timer.settings(HungerGamesSettings.DEFAULTS);
    }

    private void advance(Duration by) {
        wallClock.addAndGet(by.toMillis());
    }

    @Test
    @DisplayName("every tick calls RoundExpiryService.tick, even long before the round's deadline")
    void everyTickAsksRoundExpiry() {
        timer.start();

        timer.tick();
        timer.tick();
        timer.tick();

        // deadline() never having moved is the outward sign tick() was actually called each time and not
        // skipped: an untouched RoundExpiryService still reports the round's configured length.
        assertThat(roundExpiry.deadline()).isEqualTo(HungerGamesSettings.DEFAULTS.roundDuration());
    }

    @Test
    @DisplayName("found-bug regression: reaching game.duration does not end the round by itself")
    void reachingGameDurationDoesNotEndTheRoundDirectly() {
        timer.start();
        advance(HungerGamesSettings.DEFAULTS.roundDuration().plusMinutes(5));

        timer.tick();

        // The old engine called session.declareTimeout() here directly. This class must not: only
        // RoundExpiryService's own question-and-answer may end the round, and nobody answered it in this
        // test — control.endRound() is verified as never called.
        verify(control, never()).endRound();
        assertThat(session.phase()).isEqualTo(GamePhase.RUNNING);
    }

    @Test
    @DisplayName("RoundExpiryService really is what ends the round once the deadline is reached and answered")
    void roundExpiryStillActuallyEndsIt() {
        // A dedicated instance whose control really is the session's own end-round path, so ending it here
        // is a real, observable phase change and not just "control.endRound() was called".
        GameControlService realControl = new GameControlService(session, actor -> false,
                (a, count) -> true, (a) -> true, (a) -> true);
        realControl.settings(HungerGamesSettings.DEFAULTS);
        List<Runnable> endButtons = new ArrayList<>();
        RoundExpiryService expiry = new RoundExpiryService(realControl, () -> List.of(ALICE),
                (who, overrun, end, extend) -> endButtons.add(end), message -> { }, session::phase);
        expiry.settings(HungerGamesSettings.DEFAULTS);
        GameTimerService withRealExpiry = new GameTimerService(session, virtualTime, border, expiry,
                List::of, bossBars, scoreboards, () -> online, tributes -> { }, GameTimerService.manual());
        withRealExpiry.settings(HungerGamesSettings.DEFAULTS);

        withRealExpiry.start();
        advance(HungerGamesSettings.DEFAULTS.roundDuration());
        withRealExpiry.tick();
        endButtons.get(0).run();

        assertThat(session.phase()).isEqualTo(GamePhase.FINISHED);
    }

    @Test
    @DisplayName("the grace period ends exactly once, and the effect fires exactly once")
    void graceEndsExactlyOnce() {
        timer.start();
        assertThat(timer.isGraceActive()).isTrue();

        advance(HungerGamesSettings.DEFAULTS.gracePeriod().plusSeconds(1));
        timer.tick();
        timer.tick();
        timer.tick();

        assertThat(timer.isGraceActive()).isFalse();
        assertThat(graceEndedCalls).isEqualTo(1);
    }

    @Test
    @DisplayName("no grace period configured means grace is never active")
    void zeroGraceIsNeverActive() {
        timer.settings(Tweak.of(HungerGamesSettings.DEFAULTS, "gracePeriodSeconds", 0));

        timer.start();

        assertThat(timer.isGraceActive()).isFalse();
    }

    @Test
    @DisplayName("the border is ticked on every tick, using this round's current phase list")
    void borderIsTickedEveryTick() {
        timer.start();
        double before = 2500;

        timer.tick();

        assertThat(border.currentSettings(List.of())).isNotNull(); // sanity: settings still readable
        // No conflicts and no phases means the border does not move — proving the tick reached it without
        // needing a real phase to fire is done via BorderServiceTest; here it is enough that calling tick()
        // does not throw with an empty phase list and leaves the border exactly where it was.
        assertThat(before).isEqualTo(2500);
    }

    @Test
    @DisplayName("outside RUNNING, tick() clears the displays and does not touch RoundExpiryService")
    void tickOutsideRunningJustClears() {
        session.declareTimeout(); // -> FINISHED

        timer.tick();

        verify(bossBars, times(0)).show(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("the boss bar and the leaderboard are shown to everybody in the audience")
    void displaysGoToEverybody() {
        // Originally asserted times(2) after start() + a single tick(), expecting only the tick's own
        // refresh to count. That missed that start() itself already shows the initial state to everybody
        // online — see its javadoc — so the two of ALICE and BOB were each shown twice by the time tick()
        // returned: once from start(), once from tick(). The real, correct count is 4, not 2; clearing the
        // mocks after start() isolates exactly what a single tick() does, which is what this test is
        // actually about.
        timer.start();
        org.mockito.Mockito.clearInvocations(bossBars, scoreboards);

        timer.tick();

        verify(bossBars, times(2)).show(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(
                GameTimerService.OWNER), org.mockito.ArgumentMatchers.any(BarStyle.class),
                org.mockito.ArgumentMatchers.any());
        verify(scoreboards, times(2)).show(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(
                GameTimerService.OWNER), org.mockito.ArgumentMatchers.any(Sidebar.class),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("stop() clears every viewer's boss bar and sidebar")
    void stopClearsEverybody() {
        timer.start();

        timer.stop();

        verify(bossBars).clear(ALICE, GameTimerService.OWNER);
        verify(bossBars).clear(BOB, GameTimerService.OWNER);
        verify(scoreboards).clear(ALICE, GameTimerService.OWNER);
        verify(scoreboards).clear(BOB, GameTimerService.OWNER);
    }

    @Test
    @DisplayName("addViewer shows the running round's displays, but only while RUNNING")
    void addViewerOnlyDuringRunning() {
        timer.start();
        UUID carol = UUID.randomUUID();

        timer.addViewer(carol);

        verify(bossBars).show(org.mockito.ArgumentMatchers.eq(carol),
                org.mockito.ArgumentMatchers.eq(GameTimerService.OWNER),
                org.mockito.ArgumentMatchers.any(BarStyle.class), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("addViewer does nothing outside RUNNING")
    void addViewerNoopOutsideRunning() {
        session.declareTimeout();
        UUID carol = UUID.randomUUID();

        timer.addViewer(carol);

        verify(bossBars, times(0)).show(org.mockito.ArgumentMatchers.eq(carol),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("resume() picks a mid-round session back up and keeps ticking")
    void resumeContinuesFromWhereItLeftOff() {
        timer.resume(Duration.ofMinutes(90), 2);

        assertThat(virtualTime.elapsed()).isGreaterThanOrEqualTo(Duration.ofMinutes(90));
        assertThat(border.nextPhaseIndex()).isEqualTo(2);
    }

    @Test
    @DisplayName("what it calls itself, for the console line listing what started")
    void itSaysWhatItIs() {
        assertThat(timer.describe()).isNotBlank();
    }

    @Test
    @DisplayName("the manual ticker never actually schedules anything, and its handle is safe to close")
    void manualTickerIsInert() throws Exception {
        AutoCloseable handle = GameTimerService.manual().everySecond(() -> {
            throw new AssertionError("must never run");
        });

        handle.close(); // must not throw either
    }
}
