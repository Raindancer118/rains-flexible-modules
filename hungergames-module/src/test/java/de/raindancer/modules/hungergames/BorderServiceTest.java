package de.raindancer.modules.hungergames;

import de.raindancer.modules.hungergames.model.BorderPhaseConfig;
import de.raindancer.modules.hungergames.model.BorderSettings;
import de.raindancer.modules.hungergames.model.BorderTrigger;
import de.raindancer.modules.hungergames.model.GameClock;
import de.raindancer.modules.hungergames.rules.TeamRules;
import de.raindancer.modules.hungergames.service.BorderService;
import de.raindancer.modules.hungergames.service.VirtualTime;
import de.raindancer.modules.hungergames.store.GameSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class BorderServiceTest {

    /** Records every shrink asked of it, and answers whatever size it was last told to move to. */
    private static final class FakeTarget implements BorderService.WorldBorderTarget {
        double size = 2500;
        Double lastNetherTarget;
        long lastTicks;

        @Override
        public double currentSize() {
            return size;
        }

        @Override
        public void shrinkOverworld(double targetSize, long ticks) {
            size = targetSize;
            lastTicks = ticks;
        }

        @Override
        public void shrinkNether(double targetSize, long ticks) {
            lastNetherTarget = targetSize;
        }

        @Override
        public void resetTo(double overworldSize) {
            size = overworldSize;
            lastNetherTarget = null;
        }
    }

    private GameSession session;
    private VirtualTime virtualTime;
    private FakeTarget target;
    private BorderService border;
    private final AtomicLong wallClock = new AtomicLong();

    @BeforeEach
    void setUp() {
        session = new GameSession(TeamRules::defaults, new RecordingGameEvents(),
                new InMemorySessionStore(), GameClock.system(), new Random(0));
        session.whitelistAdd(UUID.randomUUID(), "Katniss");
        session.whitelistAdd(UUID.randomUUID(), "Peeta");
        virtualTime = new VirtualTime(wallClock::get);
        target = new FakeTarget();
        border = new BorderService(session, virtualTime, target);
        border.settings(HungerGamesSettings.DEFAULTS);
    }

    private static BorderSettings onePhaseAtStart(double targetSize) {
        return new BorderSettings(2500, 100, 2.5, List.of(
                BorderPhaseConfig.ofFixedSpeed(BorderTrigger.atTime(Duration.ZERO), targetSize, 2.0)));
    }

    @Test
    void tickAppliesAFiredPhaseToBothWorlds() {
        border.start();
        virtualTime.start();
        border.tick(onePhaseAtStart(1500));

        assertThat(target.size).isEqualTo(1500);
        assertThat(target.lastNetherTarget).isEqualTo(BorderService.netherSize(1500));
        assertThat(border.nextPhaseIndex()).isEqualTo(1);
    }

    @Test
    void aFinishedPhaseListDoesNotFireTwice() {
        border.start();
        virtualTime.start();
        BorderSettings settings = onePhaseAtStart(1500);
        border.tick(settings);
        double sizeAfterFirst = target.size;

        border.tick(settings);
        assertThat(target.size).isEqualTo(sizeAfterFirst);
    }

    @Test
    void resumeAtSkipsPhasesAlreadyFiredBeforeARestart() {
        border.resumeAt(1);
        virtualTime.start();
        border.tick(onePhaseAtStart(1500));
        // The only configured phase is index 0, already marked done, so nothing fires.
        assertThat(target.size).isEqualTo(2500);
    }

    @Test
    void overrideShrinkToStopsRegularPhasesAndShrinksBothWorlds() {
        border.start();
        long seconds = border.overrideShrinkTo(1000);

        assertThat(seconds).isGreaterThan(0);
        assertThat(target.size).isEqualTo(1000);
        assertThat(target.lastNetherTarget).isEqualTo(BorderService.netherSize(1000));

        // Regular phases are locked out: a tick after the override must not also apply a scheduled phase.
        virtualTime.start();
        border.tick(onePhaseAtStart(1500));
        assertThat(target.size).isEqualTo(1000);
    }

    @Test
    void overrideShrinkToAlreadySmallerBorderDoesNothing() {
        target.size = 500;
        assertThat(border.overrideShrinkTo(1000)).isZero();
        assertThat(target.size).isEqualTo(500);
    }

    @Test
    void shrinkSecondsRespectsTheFairnessCeiling() {
        assertThat(BorderService.shrinkSeconds(2500, 1500, 2.5)).isEqualTo(200); // (2500-1500)/2/2.5
        assertThat(BorderService.shrinkSeconds(1000, 2000, 2.5)).isZero(); // growing, not shrinking
    }

    @Test
    void netherSizeIsAnEighthAndNeverBelowSixteen() {
        assertThat(BorderService.netherSize(2500)).isEqualTo(312.5);
        assertThat(BorderService.netherSize(64)).isEqualTo(16.0);
    }

    @Test
    void resetToInitialRestoresBothWorldsAndPhaseZero() {
        border.overrideShrinkTo(1000);
        border.resetToInitial();
        assertThat(target.size).isEqualTo(HungerGamesSettings.DEFAULTS.borderInitialSize());
        assertThat(border.nextPhaseIndex()).isZero();
    }
}
