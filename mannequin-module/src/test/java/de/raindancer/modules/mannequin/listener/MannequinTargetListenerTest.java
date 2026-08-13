package de.raindancer.modules.mannequin.listener;

import de.raindancer.modules.mannequin.service.MannequinService;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityTargetEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * See the listener's own javadoc for why disabling a mannequin's own AI is not enough on its own —
 * this is what actually stops an Iron Golem mannequin (or any other kind) from being attacked by a
 * wandering hostile that would otherwise target it unprovoked.
 */
@ExtendWith(MockitoExtension.class)
class MannequinTargetListenerTest {

    @Mock
    private MannequinService mannequins;

    @Test
    @DisplayName("a hostile targeting a tracked mannequin is refused the target")
    void targetingATrackedMannequinIsCancelled() {
        UUID entityId = UUID.randomUUID();
        LivingEntity target = mock(LivingEntity.class);
        when(target.getUniqueId()).thenReturn(entityId);
        when(mannequins.isTracked(entityId)).thenReturn(true);

        EntityTargetEvent event = mock(EntityTargetEvent.class);
        when(event.getTarget()).thenReturn(target);

        new MannequinTargetListener(mannequins).onTarget(event);

        verify(event).setTarget(null);
        verify(event).setCancelled(true);
    }

    @Test
    @DisplayName("targeting anything nobody is tracking is left alone")
    void untrackedTargetIsIgnored() {
        UUID entityId = UUID.randomUUID();
        LivingEntity target = mock(LivingEntity.class);
        when(target.getUniqueId()).thenReturn(entityId);
        when(mannequins.isTracked(entityId)).thenReturn(false);

        EntityTargetEvent event = mock(EntityTargetEvent.class);
        when(event.getTarget()).thenReturn(target);

        new MannequinTargetListener(mannequins).onTarget(event);

        verify(event, never()).setTarget(org.mockito.ArgumentMatchers.any());
        verify(event, never()).setCancelled(anyBoolean());
    }

    @Test
    @DisplayName("a mob giving up its target (target == null) is not an error")
    void aNullTargetIsANoOp() {
        EntityTargetEvent event = mock(EntityTargetEvent.class);
        when(event.getTarget()).thenReturn(null);

        new MannequinTargetListener(mannequins).onTarget(event);

        verify(event, never()).setCancelled(anyBoolean());
    }

    @Test
    @DisplayName("forgetting a player is a no-op, not an error — nothing here is per-player")
    void forgetIsANoOp() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                new MannequinTargetListener(mannequins).forget(UUID.randomUUID()))).isNull();
    }
}
