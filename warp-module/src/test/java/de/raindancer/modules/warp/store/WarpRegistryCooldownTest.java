package de.raindancer.modules.warp.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That logging out is not a way past the wait between warps.
 *
 * <p>The same hole {@code /rtp} had: the quit handler dropped the player's entry outright, so
 * warping, disconnecting and coming back was a free warp. {@link WarpRegistry#leaves(UUID)} now
 * sweeps what is over instead — see its note.
 *
 * <p>The store is null: nothing here reads a warp, only the cooldown beside them.
 */
class WarpRegistryCooldownTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);

    private WarpRegistry registryWaiting(Duration between) {
        WarpRegistry registry = new WarpRegistry(null, now::get, world -> true);
        registry.cooldown(between);
        return registry;
    }

    @Test
    @DisplayName("a wait still running survives the player logging out")
    void relogDoesNotClearARunningWait() {
        WarpRegistry registry = registryWaiting(Duration.ofSeconds(60));
        UUID traveller = UUID.randomUUID();
        registry.recordUse(traveller);

        registry.leaves(traveller);

        assertThat(registry.isReadyToWarp(traveller))
                .as("reconnecting would otherwise be a free warp")
                .isFalse();
    }

    @Test
    @DisplayName("a wait that has run out is dropped when they leave")
    void quittingDropsAWaitThatIsOver() {
        WarpRegistry registry = registryWaiting(Duration.ofSeconds(60));
        UUID traveller = UUID.randomUUID();
        registry.recordUse(traveller);

        now.addAndGet(61_000L);
        registry.leaves(traveller);

        assertThat(registry.isReadyToWarp(traveller)).isTrue();
    }
}
