package de.raindancer.modules.invsnap.listener;

import de.raindancer.modules.invsnap.service.SnapshotService;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlayerDeathSnapshotListenerTest {

    @Mock
    private SnapshotService snapshots;
    @Mock
    private Player player;

    @Test
    @DisplayName("a death takes a snapshot of the dying player")
    void deathSnapshotsThePlayer() {
        PlayerDeathEvent event = mock(PlayerDeathEvent.class);
        when(event.getPlayer()).thenReturn(player);

        new PlayerDeathSnapshotListener(snapshots).onDeath(event);

        verify(snapshots).takeAndStore(player);
    }

    @Test
    @DisplayName("forgetting a player is a no-op — nothing about a death carries over to the next one")
    void forgetIsANoOp() {
        assertThatCode(() -> new PlayerDeathSnapshotListener(snapshots).forget(UUID.randomUUID()))
                .doesNotThrowAnyException();
    }
}
