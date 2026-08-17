package de.raindancer.modules.mannequin.service;

import de.raindancer.modules.mannequin.MannequinSettings;
import de.raindancer.modules.mannequin.model.Mannequin;
import de.raindancer.modules.mannequin.rules.DurabilityRule;
import de.raindancer.modules.mannequin.store.MannequinRegistry;
import de.raindancer.modules.mannequin.store.MannequinStore;
import org.bukkit.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code spawnAllIn}'s one promise: every mannequin in the world gets its own attempt, whatever
 * happens to the ones before it. Before this, one mannequin whose {@code spawn} threw took every
 * mannequin after it in the same world's registry iteration down with it — the exact shape of
 * "some of them are just gone after a restart", since which ones survived depended on registry
 * order rather than anything visible from the outside.
 */
class MannequinServiceSpawnAllInTest {

    @TempDir
    Path folder;

    @Test
    @DisplayName("one mannequin whose spawn throws does not stop the rest of the world from spawning")
    void oneFailureDoesNotStopTheRest() {
        MannequinSettings settings = MannequinSettings.DEFAULTS;
        MannequinRegistry registry = new MannequinRegistry();
        Mannequin broken = Mannequin.freshlyPlaced("MQ1", UUID.randomUUID(), "world", 0, 64, 0);
        Mannequin fine = Mannequin.freshlyPlaced("MQ2", UUID.randomUUID(), "world", 5, 64, 5);
        registry.put(broken);
        registry.put(fine);

        MannequinService service = spy(new MannequinService(null, null, registry,
                new MannequinStore(folder), new MannequinEquipService(new DurabilityRule(), settings),
                (delay, task) -> { }, settings));
        doThrow(new IllegalStateException("a corrupted loadout entry, say"))
                .when(service).spawn(broken);
        doReturn(null).when(service).spawn(fine);

        World world = mock(World.class);
        when(world.getName()).thenReturn("world");

        service.spawnAllIn(world);

        verify(service).spawn(broken);
        verify(service).spawn(fine);
    }
}
