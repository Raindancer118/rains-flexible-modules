package de.raindancer.modules.rtp.service;

import de.raindancer.modules.rtp.RtpSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That logging out is not a way past the wait between goes.
 *
 * <p>The bug this is written against: {@code /rtp}, then disconnect and come back, and the wait was
 * gone — the quit handler dropped the player's entry outright, which is the same thing as handing
 * out a free go to anybody willing to press the reconnect button. Everything here is about
 * {@link RtpService#leaves(UUID)}: it has to bound the map without ever shortening a wait that is
 * still running.
 *
 * <p>The clock is handed in rather than slept through, the same way {@code Cooldowns} takes one.
 */
class RtpServiceCooldownTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);

    /**
     * Nothing but the cooldown is touched by any of this, so the world around the service is null —
     * a Bukkit server is not needed to answer "is this player still waiting".
     */
    private RtpService serviceWaiting(int seconds) {
        return new RtpService(null, null, null, null, null, null, null, null, null,
                RtpSettings.DEFAULTS.withCooldownSeconds(seconds), new Random(), now::get);
    }

    @Test
    @DisplayName("a wait still running survives the player logging out")
    void relogDoesNotClearARunningWait() {
        RtpService service = serviceWaiting(60);
        UUID traveller = UUID.randomUUID();
        service.waits().start(traveller);

        service.leaves(traveller);

        assertThat(service.waits().isReady(traveller))
                .as("reconnecting would otherwise be a free /rtp")
                .isFalse();
        assertThat(service.waits().tracked()).isEqualTo(1);
    }

    @Test
    @DisplayName("a wait that has run out is dropped when they leave")
    void quittingDropsAWaitThatIsOver() {
        RtpService service = serviceWaiting(60);
        UUID traveller = UUID.randomUUID();
        service.waits().start(traveller);

        now.addAndGet(61_000L);
        service.leaves(traveller);

        assertThat(service.waits().tracked())
                .as("the map still has to be bounded, or it grows by a player a day forever")
                .isZero();
    }

    @Test
    @DisplayName("somebody else's finished wait goes with it, so nobody unseen is kept forever")
    void quittingSweepsWaitsThatAreOver() {
        RtpService service = serviceWaiting(60);
        UUID longGone = UUID.randomUUID();
        UUID leaving = UUID.randomUUID();
        service.waits().start(longGone);

        now.addAndGet(61_000L);
        service.waits().start(leaving);
        service.leaves(leaving);

        assertThat(service.waits().tracked()).isEqualTo(1);
        assertThat(service.waits().isReady(leaving)).isFalse();
        assertThat(service.waits().isReady(longGone)).isTrue();
    }

    @Test
    @DisplayName("with the cooldown switched off nothing is kept at all")
    void noCooldownKeepsNobody() {
        RtpService service = serviceWaiting(60);
        UUID traveller = UUID.randomUUID();
        service.waits().start(traveller);

        service.settings(RtpSettings.DEFAULTS.withCooldownSeconds(0));
        service.leaves(traveller);

        assertThat(service.waits().tracked()).isZero();
    }

    @Test
    @DisplayName("being handed nobody is not an error")
    void nullIsHarmless() {
        RtpService service = serviceWaiting(60);
        service.leaves(null);
        assertThat(service.waits().tracked()).isZero();
    }
}
