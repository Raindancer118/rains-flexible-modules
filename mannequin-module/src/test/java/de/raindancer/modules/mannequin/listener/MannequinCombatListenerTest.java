package de.raindancer.modules.mannequin.listener;

import de.raindancer.modules.mannequin.model.Mannequin;
import de.raindancer.modules.mannequin.service.MannequinCombatService;
import de.raindancer.modules.mannequin.store.MannequinRegistry;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A tracked mannequin is no longer invulnerable — see the listener's own javadoc — so this pins
 * exactly what is left of its job: recording the hit, and never touching cancellation at all.
 */
@ExtendWith(MockitoExtension.class)
class MannequinCombatListenerTest {

    @Mock
    private MannequinCombatService combat;

    private final MannequinRegistry registry = new MannequinRegistry();

    @Test
    @DisplayName("a by-player hit on a tracked mannequin is recorded and never cancelled")
    void aByPlayerHitIsRecorded() {
        UUID entityId = UUID.randomUUID();
        Mannequin mannequin = Mannequin.freshlyPlaced("MQ1", UUID.randomUUID(), "world", 0, 64, 0);
        registry.put(mannequin);
        registry.bindEntity("MQ1", entityId);

        org.bukkit.entity.Mannequin live = mock(org.bukkit.entity.Mannequin.class);
        when(live.getUniqueId()).thenReturn(entityId);
        Player attacker = mock(Player.class);
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getEntity()).thenReturn(live);
        when(event.getDamager()).thenReturn(attacker);
        when(event.getFinalDamage()).thenReturn(7.5);

        new MannequinCombatListener(registry, combat).onDamage(event);

        verify(combat).recordHit(eq(mannequin), eq(live), eq(attacker), eq(7.5), anyLong());
        verify(event, never()).setCancelled(org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("a hit on an entity nobody is tracking is ignored")
    void anUntrackedEntityIsIgnored() {
        org.bukkit.entity.Mannequin live = mock(org.bukkit.entity.Mannequin.class);
        when(live.getUniqueId()).thenReturn(UUID.randomUUID());
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getEntity()).thenReturn(live);

        new MannequinCombatListener(registry, combat).onDamage(event);

        verifyNoInteractions(combat);
    }

    @Test
    @DisplayName("a non-player damager on a tracked mannequin records nothing")
    void aNonPlayerDamagerRecordsNothing() {
        UUID entityId = UUID.randomUUID();
        registry.put(Mannequin.freshlyPlaced("MQ1", UUID.randomUUID(), "world", 0, 64, 0));
        registry.bindEntity("MQ1", entityId);

        org.bukkit.entity.Mannequin live = mock(org.bukkit.entity.Mannequin.class);
        when(live.getUniqueId()).thenReturn(entityId);
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getEntity()).thenReturn(live);
        when(event.getDamager()).thenReturn(mock(org.bukkit.entity.Zombie.class));

        new MannequinCombatListener(registry, combat).onDamage(event);

        verifyNoInteractions(combat);
    }
}
