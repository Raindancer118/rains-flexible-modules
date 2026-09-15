package de.raindancer.modules.manhunt.service;

import de.raindancer.modules.manhunt.model.Hunt;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Nobody is left flying through walls by a hunt that no longer exists. */
class SpectatorRestoreListenerTest {

    private static final UUID ANNA = UUID.nameUUIDFromBytes("anna".getBytes());
    private static final UUID BEN = UUID.nameUUIDFromBytes("ben".getBytes());

    private static Player marked(UUID id, Eliminations eliminations, boolean isMarked) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(eliminations.isMarked(player)).thenReturn(isMarked);
        return player;
    }

    @SuppressWarnings("deprecation")
    private static PlayerJoinEvent joinOf(Player player) {
        return new PlayerJoinEvent(player, (net.kyori.adventure.text.Component) null);
    }

    @Test
    @DisplayName("somebody caught in a hunt that has since ended is put back on join")
    void restoredWhenTheHuntIsGone() {
        Eliminations eliminations = mock(Eliminations.class);
        Player anna = marked(ANNA, eliminations, true);
        var listener = new SpectatorRestoreListener(Optional::empty, eliminations);

        listener.onJoin(joinOf(anna));

        verify(eliminations).restore(anna);
    }

    @Test
    @DisplayName("somebody still out of a hunt that is still going keeps watching")
    void leftWatchingWhileTheHuntRuns() {
        Eliminations eliminations = mock(Eliminations.class);
        Player anna = marked(ANNA, eliminations, true);
        Hunt hunt = Hunt.of(Set.of(ANNA, BEN), Set.of(ANNA));
        hunt.eliminate(ANNA);
        var listener = new SpectatorRestoreListener(() -> Optional.of(hunt), eliminations);

        listener.onJoin(joinOf(anna));

        verify(eliminations, never()).restore(anna);
    }

    @Test
    @DisplayName("a spectator this module never made is never touched")
    void somebodyElsesSpectator() {
        Eliminations eliminations = mock(Eliminations.class);
        Player staff = marked(BEN, eliminations, false);
        var listener = new SpectatorRestoreListener(Optional::empty, eliminations);

        listener.onJoin(joinOf(staff));

        verify(eliminations, never()).restore(staff);
    }
}
