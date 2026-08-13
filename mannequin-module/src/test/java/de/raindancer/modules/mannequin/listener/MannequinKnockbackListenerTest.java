package de.raindancer.modules.mannequin.listener;

import de.raindancer.modules.mannequin.service.MannequinService;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import org.bukkit.entity.LivingEntity;
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
 * {@code setGravity(false)} in {@code MannequinService} stops a non-{@code PLAYER}-kind mannequin
 * falling; this is the other half — nothing knocks it sideways either. See the listener's own
 * javadoc for why every source of knockback is cancelled, not only a by-player hit.
 */
@ExtendWith(MockitoExtension.class)
class MannequinKnockbackListenerTest {

    @Mock
    private MannequinService mannequins;

    @Test
    @DisplayName("any knockback on a tracked mannequin is cancelled")
    void trackedMannequinKnockbackIsCancelled() {
        UUID entityId = UUID.randomUUID();
        when(mannequins.isTracked(entityId)).thenReturn(true);

        LivingEntity live = mock(LivingEntity.class);
        when(live.getUniqueId()).thenReturn(entityId);
        EntityKnockbackEvent event = mock(EntityKnockbackEvent.class);
        when(event.getEntity()).thenReturn(live);

        new MannequinKnockbackListener(mannequins).onKnockback(event);

        verify(event).setCancelled(true);
    }

    @Test
    @DisplayName("knockback on an entity nobody is tracking is left alone")
    void untrackedEntityIsIgnored() {
        UUID entityId = UUID.randomUUID();
        when(mannequins.isTracked(entityId)).thenReturn(false);

        LivingEntity live = mock(LivingEntity.class);
        when(live.getUniqueId()).thenReturn(entityId);
        EntityKnockbackEvent event = mock(EntityKnockbackEvent.class);
        when(event.getEntity()).thenReturn(live);

        new MannequinKnockbackListener(mannequins).onKnockback(event);

        verify(event, never()).setCancelled(anyBoolean());
    }

    @Test
    @DisplayName("forgetting a player is a no-op, not an error — nothing here is per-player")
    void forgetIsANoOp() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                new MannequinKnockbackListener(mannequins).forget(UUID.randomUUID()))).isNull();
    }
}
