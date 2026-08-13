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

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The half of {@link MannequinService#ensureBarrel} that does not need a live world: it must not
 * ask {@code Bukkit} for anything when there is nothing to place a barrel for. Placing the barrel
 * itself needs {@code World#getBlockAt}, which — like {@link MannequinService#spawn} — cannot run
 * without a real server; that half is covered by code review, the same limitation this module's
 * other live-entity paths already carry.
 */
class MannequinServiceEnsureBarrelTest {

    @TempDir
    Path folder;

    private MannequinService service(MannequinRegistry registry, MannequinSettings settings) {
        return new MannequinService(null, null, registry, new MannequinStore(folder),
                new MannequinEquipService(new DurabilityRule(), settings), (delay, task) -> { }, settings);
    }

    @Test
    @DisplayName("does nothing for a mannequin that has not opted into the redstone signal")
    void notOptedInDoesNothing() {
        MannequinRegistry registry = new MannequinRegistry();
        Mannequin mannequin = Mannequin.freshlyPlaced("MQ1", UUID.randomUUID(), "world", 0, 64, 0);
        registry.put(mannequin);
        registry.bindEntity("MQ1", UUID.randomUUID());

        // No live server behind Bukkit.getEntity in this test — if ensureBarrel tried to resolve
        // the live entity it would throw, so reaching the end of this call proves it bailed out on
        // the opt-in check before ever asking for one.
        assertThatCode(() -> service(registry, MannequinSettings.DEFAULTS).ensureBarrel(mannequin))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("does nothing for a mannequin with no live entity bound, even if opted in")
    void noLiveEntityDoesNothing() {
        MannequinRegistry registry = new MannequinRegistry();
        Mannequin mannequin = Mannequin.freshlyPlaced("MQ1", UUID.randomUUID(), "world", 0, 64, 0)
                .withEmitsRedstoneSignal(true);
        registry.put(mannequin);
        // Deliberately never bound — the world this mannequin belongs to is not loaded right now.

        assertThatCode(() -> service(registry, MannequinSettings.DEFAULTS).ensureBarrel(mannequin))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a null mannequin is a no-op, not a NullPointerException")
    void nullIsANoOp() {
        assertThatCode(() -> service(new MannequinRegistry(), MannequinSettings.DEFAULTS).ensureBarrel(null))
                .doesNotThrowAnyException();
    }
}
