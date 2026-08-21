package de.raindancer.modules.farmworld.service;

import de.raindancer.modules.farmworld.FarmWorldSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That logging out is not a way past the wait between farm world trips.
 *
 * <p>The same hole {@code /rtp} had — see {@link FarmTravelService#leaves(UUID)}. Everything around
 * the cooldown is null here: answering "is this player still waiting" needs no server.
 */
class FarmTravelServiceCooldownTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);

    private FarmTravelService serviceWaiting(int seconds) {
        return new FarmTravelService(null, null, null, null, null,
                FarmWorldSettings.DEFAULTS.withCooldownSeconds(seconds), new Random(), now::get);
    }

    @Test
    @DisplayName("a wait still running survives the player logging out")
    void relogDoesNotClearARunningWait() {
        FarmTravelService service = serviceWaiting(120);
        UUID traveller = UUID.randomUUID();
        service.waits().start(traveller);

        service.leaves(traveller);

        assertThat(service.waits().isReady(traveller))
                .as("reconnecting would otherwise be a free trip")
                .isFalse();
        assertThat(service.waits().tracked()).isEqualTo(1);
    }

    @Test
    @DisplayName("a wait that has run out is dropped when they leave")
    void quittingDropsAWaitThatIsOver() {
        FarmTravelService service = serviceWaiting(120);
        UUID traveller = UUID.randomUUID();
        service.waits().start(traveller);

        now.addAndGet(121_000L);
        service.leaves(traveller);

        assertThat(service.waits().tracked()).isZero();
    }
}
