package de.raindancer.modules.tpa.service;

import de.raindancer.core.world.teleport.Travel;
import de.raindancer.modules.tpa.TpaSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * That logging out is not a way past either of this module's waits.
 *
 * <p>The hole {@code /rtp} had, and both services here had it too: the quit handler dropped the
 * player's entry outright, so {@code /tpa} or {@code /back}, disconnect, reconnect and go again cost
 * nothing. See {@code leaves} on each service.
 */
class TpaCooldownAcrossLogoutTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);

    @Nested
    @DisplayName("the wait between requests")
    class Asking {

        private TpaRequestService service() {
            return new TpaRequestService(null, new de.raindancer.modules.tpa.store.TpaRequests(now::get),
                    null, null, null, null, null, null, TpaSettings.DEFAULTS, now::get);
        }

        @Test
        @DisplayName("a wait still running survives the player logging out")
        void relogDoesNotClearARunningWait() {
            TpaRequestService service = service();
            UUID asker = UUID.randomUUID();
            service.waits().start(asker);

            service.leaves(asker);

            assertThat(service.waits().isReady(asker))
                    .as("reconnecting would otherwise be a free /tpa")
                    .isFalse();
        }

        @Test
        @DisplayName("a wait that has run out is dropped when they leave")
        void quittingDropsAWaitThatIsOver() {
            TpaRequestService service = service();
            UUID asker = UUID.randomUUID();
            service.waits().start(asker);

            now.addAndGet(TpaSettings.DEFAULTS.cooldown() * 1000L + 1_000L);
            service.leaves(asker);

            assertThat(service.waits().tracked()).isZero();
        }
    }

    @Nested
    @DisplayName("the wait between /back")
    class GoingBack {

        private BackService service() {
            return new BackService(mock(Travel.class), null, TpaSettings.DEFAULTS, now::get);
        }

        @Test
        @DisplayName("a wait still running survives the player logging out")
        void relogDoesNotClearARunningWait() {
            BackService service = service();
            UUID traveller = UUID.randomUUID();
            service.waits().start(traveller);

            service.leaves(traveller);

            assertThat(service.waits().isReady(traveller))
                    .as("reconnecting would otherwise be a free /back")
                    .isFalse();
        }

        @Test
        @DisplayName("a wait that has run out is dropped when they leave")
        void quittingDropsAWaitThatIsOver() {
            BackService service = service();
            UUID traveller = UUID.randomUUID();
            service.waits().start(traveller);

            now.addAndGet(TpaSettings.DEFAULTS.backCooldown() * 1000L + 1_000L);
            service.leaves(traveller);

            assertThat(service.waits().tracked()).isZero();
        }
    }
}
