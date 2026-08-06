package de.raindancer.modules.hungergames;

import de.raindancer.modules.hungergames.service.VirtualTime;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The clock {@code /speedup} bends, exercised with a fake wall clock so the multiplier's effect can be
 * asserted exactly rather than approximated by sleeping in a test.
 */
class VirtualTimeTest {

    private final AtomicLong wallClock = new AtomicLong(0);
    private final VirtualTime time = new VirtualTime(wallClock::get);

    @Test
    void atRestNormalSpeedTicksOneToOne() {
        time.start();
        wallClock.addAndGet(5_000);
        assertThat(time.elapsed()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void multiplierScalesFutureElapsedTimeOnly() {
        time.start();
        wallClock.addAndGet(2_000);
        assertThat(time.elapsed()).isEqualTo(Duration.ofSeconds(2));

        // The two seconds already accumulated at 1x must not be rewritten retroactively — only what
        // elapses from here on runs at the new pace. Rescaling the past would make a round's own log
        // disagree with itself about how long the opening two seconds took.
        time.setMultiplier(3.0);
        wallClock.addAndGet(2_000);
        assertThat(time.elapsed()).isEqualTo(Duration.ofSeconds(8));
    }

    @Test
    void multiplierBelowOneIsRefused() {
        // 1.0 is the floor, not a suggestion: a plugin cannot use /speedup to run a round in slow motion,
        // because nothing downstream — grace period, border phases — was ever designed to be told about
        // that and a round that never finishes is worse than one that runs at the pace it was configured for.
        time.start();
        time.setMultiplier(0.2);
        assertThat(time.multiplier()).isEqualTo(1.0);
    }

    @Test
    void stopFreezesTheReading() {
        time.start();
        wallClock.addAndGet(3_000);
        time.stop();
        wallClock.addAndGet(10_000);
        assertThat(time.elapsed()).isEqualTo(Duration.ofSeconds(3));
        assertThat(time.isRunning()).isFalse();
    }

    @Test
    void resumeAtContinuesFromASavedOffset() {
        // What a restart needs: the round's elapsed time survived in session.yml as a plain Duration, and
        // resuming has to carry on from there rather than from zero — otherwise a server crash at the
        // fifty-minute mark hands back a round with fifty minutes still thought to be ahead of it.
        time.resumeAt(Duration.ofMinutes(50));
        wallClock.addAndGet(1_000);
        assertThat(time.elapsed()).isEqualTo(Duration.ofMinutes(50).plusSeconds(1));
    }
}
