package de.raindancer.modules.mannequin.service;

import de.raindancer.modules.mannequin.MannequinSettings;
import de.raindancer.modules.mannequin.rules.DurabilityRule;
import de.raindancer.modules.mannequin.store.MannequinRegistry;
import de.raindancer.modules.mannequin.store.MannequinStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The guard half of {@link MannequinService#lookupAndApplySkinByUsername} — everything that can
 * run without a live server, which is everything up to the point this would call {@code
 * Bukkit.createProfile(...)} for a real Mojang lookup. That half is verified by code review
 * against the real API instead, the same limitation this module's other live-entity skin code
 * already carries; with no {@code Plugin} to construct here, every path through this test
 * necessarily exercises the "nothing to do" guard rather than isolating individual conditions —
 * still worth pinning, since a guard that regresses into throwing instead of reporting
 * {@code onNotFound} would surface as a broken chat prompt on a live server.
 */
class MannequinServiceSkinLookupTest {

    @TempDir
    Path folder;

    private MannequinService service() {
        MannequinSettings settings = MannequinSettings.DEFAULTS;
        return new MannequinService(null, null, new MannequinRegistry(), new MannequinStore(folder),
                new MannequinEquipService(new DurabilityRule(), settings), (delay, task) -> { }, settings);
    }

    @Test
    @DisplayName("with nothing to look anything up with, onNotFound is told rather than anything thrown")
    void reportsNotFoundRatherThanThrowing() {
        boolean[] notFound = {false};

        service().lookupAndApplySkinByUsername("MQ1", "Notch", name -> { }, () -> notFound[0] = true);

        assertThat(notFound[0]).isTrue();
    }

    @Test
    @DisplayName("a missing onNotFound callback does not turn the guard itself into a crash")
    void missingCallbackDoesNotThrow() {
        assertThatCode(() -> service().lookupAndApplySkinByUsername("MQ1", "Notch", name -> { }, null))
                .doesNotThrowAnyException();
    }
}
