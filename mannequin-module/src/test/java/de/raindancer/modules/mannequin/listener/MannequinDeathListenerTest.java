package de.raindancer.modules.mannequin.listener;

import de.raindancer.modules.mannequin.model.Mannequin;
import de.raindancer.modules.mannequin.service.MannequinService;
import de.raindancer.modules.mannequin.store.MannequinRegistry;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MannequinDeathListenerTest {

    @Mock
    private MannequinService mannequins;

    private final MannequinRegistry registry = new MannequinRegistry();

    @Test
    @DisplayName("a tracked mannequin's death clears every drop and zeroes experience, unconditionally")
    void deathClearsDropsAndExp() {
        UUID entityId = UUID.randomUUID();
        Mannequin mannequin = Mannequin.freshlyPlaced("MQ1", UUID.randomUUID(), "world", 0, 64, 0);
        registry.put(mannequin);
        registry.bindEntity("MQ1", entityId);

        org.bukkit.entity.Mannequin live = mock(org.bukkit.entity.Mannequin.class);
        when(live.getUniqueId()).thenReturn(entityId);
        EntityDeathEvent event = mock(EntityDeathEvent.class);
        when(event.getEntity()).thenReturn(live);
        List<ItemStack> drops = new ArrayList<>(List.of(mock(ItemStack.class)));
        when(event.getDrops()).thenReturn(drops);

        new MannequinDeathListener(registry, mannequins).onDeath(event);

        assertThat(drops).isEmpty();
        verify(event).setDroppedExp(0);
    }

    @Test
    @DisplayName("a death schedules the identical respawn")
    void deathSchedulesRespawn() {
        UUID entityId = UUID.randomUUID();
        Mannequin mannequin = Mannequin.freshlyPlaced("MQ1", UUID.randomUUID(), "world", 0, 64, 0);
        registry.put(mannequin);
        registry.bindEntity("MQ1", entityId);

        org.bukkit.entity.Mannequin live = mock(org.bukkit.entity.Mannequin.class);
        when(live.getUniqueId()).thenReturn(entityId);
        EntityDeathEvent event = mock(EntityDeathEvent.class);
        when(event.getEntity()).thenReturn(live);
        when(event.getDrops()).thenReturn(new ArrayList<>());

        new MannequinDeathListener(registry, mannequins).onDeath(event);

        verify(mannequins).scheduleRespawn(mannequin);
    }

    @Test
    @DisplayName("an untracked entity's death is not this module's business")
    void untrackedEntityIsIgnored() {
        org.bukkit.entity.Mannequin live = mock(org.bukkit.entity.Mannequin.class);
        when(live.getUniqueId()).thenReturn(UUID.randomUUID());
        EntityDeathEvent event = mock(EntityDeathEvent.class);
        when(event.getEntity()).thenReturn(live);

        new MannequinDeathListener(registry, mannequins).onDeath(event);

        verifyNoInteractions(mannequins);
    }
}
