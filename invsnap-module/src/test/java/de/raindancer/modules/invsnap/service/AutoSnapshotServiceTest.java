package de.raindancer.modules.invsnap.service;

import de.raindancer.modules.invsnap.InvSnapSettings;
import de.raindancer.modules.invsnap.rules.SnapshotDueRule;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code SnapshotService} itself is mocked throughout — it is the one class in this module that
 * touches a real {@code ItemStack}, which (see {@code MannequinEquipServiceTest}'s own javadoc for
 * the same limitation elsewhere in this reactor) lazily reaches for a running Paper server. What is
 * tested here is purely {@code AutoSnapshotService}'s own decision: whether a player is due another
 * snapshot right now, and that it remembers having taken one.
 */
@ExtendWith(MockitoExtension.class)
class AutoSnapshotServiceTest {

    @Mock
    private SnapshotService snapshots;
    @Mock
    private Player player;

    private final UUID playerId = UUID.randomUUID();
    private AutoSnapshotService service;

    @BeforeEach
    void setUp() {
        service = new AutoSnapshotService(snapshots, new SnapshotDueRule(),
                new InvSnapSettings(300, 24));
    }

    @Test
    @DisplayName("a player nothing is remembered about yet is snapshotted at once")
    void firstTickAlwaysSnapshots() {
        when(player.getUniqueId()).thenReturn(playerId);

        service.tick(List.of(player), Instant.now());

        verify(snapshots).takeAndStore(player);
    }

    @Test
    @DisplayName("a second tick inside the interval takes nothing more")
    void secondTickInsideTheIntervalDoesNothing() {
        when(player.getUniqueId()).thenReturn(playerId);
        Instant first = Instant.now();
        service.tick(List.of(player), first);
        service.tick(List.of(player), first.plusSeconds(10));

        verify(snapshots, org.mockito.Mockito.times(1)).takeAndStore(any());
    }

    @Test
    @DisplayName("once the interval has passed, another snapshot is taken")
    void afterTheIntervalAnotherIsTaken() {
        when(player.getUniqueId()).thenReturn(playerId);
        Instant first = Instant.now();
        service.tick(List.of(player), first);
        service.tick(List.of(player), first.plusSeconds(301));

        verify(snapshots, org.mockito.Mockito.times(2)).takeAndStore(player);
    }

    @Test
    @DisplayName("forgetting a player means their very next tick snapshots again immediately")
    void forgettingResetsTheClock() {
        when(player.getUniqueId()).thenReturn(playerId);
        Instant first = Instant.now();
        service.tick(List.of(player), first);
        service.forget(playerId);
        service.tick(List.of(player), first.plusSeconds(1));

        verify(snapshots, org.mockito.Mockito.times(2)).takeAndStore(player);
    }

    @Test
    @DisplayName("an updated interval is honoured on the very next tick, not only after a restart")
    void aChangedIntervalTakesEffectImmediately() {
        when(player.getUniqueId()).thenReturn(playerId);
        Instant first = Instant.now();
        service.tick(List.of(player), first);

        // The floor in InvSnapSettings#snapshotInterval is 30 seconds, so this is the shortest
        // interval that can actually take effect.
        service.settings(new InvSnapSettings(30, 24));
        service.tick(List.of(player), first.plusSeconds(31));

        verify(snapshots, org.mockito.Mockito.times(2)).takeAndStore(player);
    }

    @Test
    @DisplayName("nobody online means nothing happens")
    void nobodyOnlineDoesNothing() {
        service.tick(List.of(), Instant.now());

        verify(snapshots, never()).takeAndStore(any());
    }
}
