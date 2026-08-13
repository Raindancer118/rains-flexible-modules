package de.raindancer.modules.mannequin.service;

import de.raindancer.modules.mannequin.MannequinSettings;
import de.raindancer.modules.mannequin.model.Mannequin;
import de.raindancer.modules.mannequin.rules.DurabilityRule;
import de.raindancer.modules.mannequin.store.MannequinRegistry;
import de.raindancer.modules.mannequin.store.MannequinStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The respawn scheduling half of {@code MannequinService}, using a fake {@link
 * MannequinService.DelayedScheduler} — actually running the respawn task needs a live server
 * ({@code Bukkit.getWorld}), so this pins what can be pinned without one: the right delay is asked
 * for, and the mannequin's live-entity binding and training tally are cleared immediately rather
 * than waiting for the delay to elapse.
 */
class MannequinServiceRespawnTest {

    @TempDir
    Path folder;

    private record Recorded(long delayTicks, Runnable task) {
    }

    @Test
    @DisplayName("scheduleRespawn asks for exactly the configured delay, in ticks")
    void asksForTheConfiguredDelay() {
        MannequinSettings settings = MannequinSettings.DEFAULTS.withRespawnDelaySeconds(3);
        MannequinRegistry registry = new MannequinRegistry();
        Mannequin mannequin = Mannequin.freshlyPlaced("MQ1", UUID.randomUUID(), "world", 0, 64, 0);
        registry.put(mannequin);
        registry.bindEntity("MQ1", UUID.randomUUID());

        Recorded[] captured = new Recorded[1];
        MannequinService.DelayedScheduler fake = (delay, task) -> captured[0] = new Recorded(delay, task);

        MannequinService service = new MannequinService(null, null, registry,
                new MannequinStore(folder), new MannequinEquipService(new DurabilityRule(), settings),
                fake, settings);

        service.scheduleRespawn(mannequin);

        assertThat(captured[0]).isNotNull();
        assertThat(captured[0].delayTicks()).isEqualTo(60L); // 3 seconds * 20 ticks
        assertThat(captured[0].task()).isNotNull();
    }

    @Test
    @DisplayName("the live-entity binding and training tally are cleared right away, not after the delay")
    void clearsStateImmediately() {
        MannequinSettings settings = MannequinSettings.DEFAULTS;
        MannequinRegistry registry = new MannequinRegistry();
        Mannequin mannequin = Mannequin.freshlyPlaced("MQ1", UUID.randomUUID(), "world", 0, 64, 0);
        registry.put(mannequin);
        registry.bindEntity("MQ1", UUID.randomUUID());
        registry.updateSession("MQ1", registry.sessionFor("MQ1").hit(15.0, 1000L, false));

        MannequinService service = new MannequinService(null, null, registry,
                new MannequinStore(folder), new MannequinEquipService(new DurabilityRule(), settings),
                (delay, task) -> { /* never fired: this test only checks the immediate side effects */ },
                settings);

        service.scheduleRespawn(mannequin);

        assertThat(registry.liveEntity("MQ1")).isEmpty();
        assertThat(registry.sessionFor("MQ1").hitCount()).isZero();
        // The stored record itself must survive a death — a respawn resurrects it, it does not
        // recreate a mannequin from nothing.
        assertThat(registry.get("MQ1")).isPresent();
    }

    @Test
    @DisplayName("a zero-second delay is still a real scheduled call, not an immediate respawn")
    void zeroDelayIsStillScheduled() {
        MannequinSettings settings = MannequinSettings.DEFAULTS.withRespawnDelaySeconds(0);
        MannequinRegistry registry = new MannequinRegistry();
        Mannequin mannequin = Mannequin.freshlyPlaced("MQ1", UUID.randomUUID(), "world", 0, 64, 0);
        registry.put(mannequin);

        boolean[] called = {false};
        MannequinService service = new MannequinService(null, null, registry,
                new MannequinStore(folder), new MannequinEquipService(new DurabilityRule(), settings),
                (delay, task) -> called[0] = true, settings);

        service.scheduleRespawn(mannequin);

        assertThat(called[0]).isTrue();
    }
}
