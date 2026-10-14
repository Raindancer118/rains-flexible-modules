package de.raindancer.modules.farmworld.service;

import de.raindancer.core.platform.network.NetworkCooldowns;
import de.raindancer.core.platform.util.Cooldowns;
import de.raindancer.modules.farmworld.FarmWorldSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The wait between farm world trips follows the player; a test's own clock still keeps it to itself. */
class FarmTravelCooldownSharedTest {

    @BeforeEach
    void network() {
        NetworkCooldowns.install(new NetworkCooldowns(null, System::currentTimeMillis, task -> { }));
    }

    @AfterEach
    void standAlone() {
        NetworkCooldowns.install(null);
    }

    private static FarmTravelService service(java.util.function.LongSupplier clock) {
        return new FarmTravelService(null, null, null, null, null,
                FarmWorldSettings.DEFAULTS.withCooldownSeconds(120), new Random(), clock);
    }

    @Test
    @DisplayName("a trip on one server starts the wait on every other")
    void followsThePlayer() {
        UUID traveller = UUID.randomUUID();
        service(null).waits().start(traveller);

        assertThat(service(null).waits().isReady(traveller)).isFalse();
        assertThat(service(null).waits()).isSameAs(Cooldowns.shared("farmworld:travel"));
    }

    @Test
    @DisplayName("a clock handed in gives a wait of its own")
    void aTestClockStaysLocal() {
        assertThat(service(System::currentTimeMillis).waits()).isNotSameAs(Cooldowns.shared("farmworld:travel"));
    }
}
