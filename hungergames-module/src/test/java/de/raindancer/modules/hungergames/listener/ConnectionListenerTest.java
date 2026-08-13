package de.raindancer.modules.hungergames.listener;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.InMemorySessionStore;
import de.raindancer.modules.hungergames.RecordingGameEvents;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.rules.TeamRules;
import de.raindancer.modules.hungergames.service.ArenaBuildService;
import de.raindancer.modules.hungergames.service.GameTimerService;
import de.raindancer.modules.hungergames.service.SpectatorService;
import de.raindancer.modules.hungergames.service.TeamPresentationService;
import de.raindancer.modules.hungergames.store.GameSession;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ConnectionListener#onJoin} — the fix for the same bug shape that emptied a player's real
 * inventory on an entirely different server: forcing spectator mode, or handing back an eliminated
 * tribute's forced-spectator state, based only on {@code GamePhase} and never on which world the joining
 * player is actually standing in. A stranger joining the main survival world while a round runs elsewhere
 * must not be shoved into spectator just because a tournament happens to be RUNNING somewhere on the
 * server.
 */
class ConnectionListenerTest {

    private GameSession session;
    private SpectatorService spectators;
    private ArenaBuildService arena;
    private ConnectionListener listener;
    private final World arenaWorld = mock(World.class);
    private final World otherWorld = mock(World.class);

    private final UUID tributeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        session = new GameSession(TeamRules::defaults, new RecordingGameEvents(),
                new InMemorySessionStore(), () -> 0L, new Random(1));
        session.whitelistAdd(tributeId, "Tribute");
        session.transitionTo(GamePhase.PREFLIGHT);
        session.transitionTo(GamePhase.LOBBY);
        session.transitionTo(GamePhase.STARTUP);
        session.transitionTo(GamePhase.READY);
        session.transitionTo(GamePhase.RUNNING);

        spectators = mock(SpectatorService.class);
        arena = mock(ArenaBuildService.class);
        lenient().when(arena.arenaWorld()).thenReturn(Optional.of(arenaWorld));

        listener = new ConnectionListener(session, spectators,
                mock(TeamPresentationService.class), mock(GameTimerService.class),
                mock(Messages.class), message -> { }, HungerGamesSettings.DEFAULTS, arena);
    }

    private Player playerIn(World world) {
        Player player = mock(Player.class);
        lenient().when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        lenient().when(player.getName()).thenReturn("Stranger");
        lenient().when(player.getWorld()).thenReturn(world);
        return player;
    }

    private PlayerJoinEvent joinEventFor(Player player) {
        PlayerJoinEvent event = mock(PlayerJoinEvent.class);
        when(event.getPlayer()).thenReturn(player);
        return event;
    }

    @Test
    void aStrangerJoiningTheArenaWorldWhileTheRoundRunsIsMadeASpectator() {
        Player stranger = playerIn(arenaWorld);

        listener.onJoin(joinEventFor(stranger));

        verify(stranger).setGameMode(GameMode.SPECTATOR);
    }

    @Test
    void aStrangerJoiningAnUnrelatedWorldIsLeftAlone() {
        Player stranger = playerIn(otherWorld);

        listener.onJoin(joinEventFor(stranger));

        verify(stranger, never()).setGameMode(any());
    }

    @Test
    void anEliminatedTributeRejoiningAnUnrelatedWorldIsNotForcedIntoSpectator() {
        session.eliminate(tributeId, null);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(tributeId);
        lenient().when(player.getName()).thenReturn("Tribute");
        lenient().when(player.getWorld()).thenReturn(otherWorld);

        listener.onJoin(joinEventFor(player));

        verify(spectators, never()).makeSpectator(any());
    }

    @Test
    void anEliminatedTributeRejoiningTheArenaWorldIsMadeASpectator() {
        session.eliminate(tributeId, null);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(tributeId);
        lenient().when(player.getName()).thenReturn("Tribute");
        lenient().when(player.getWorld()).thenReturn(arenaWorld);

        listener.onJoin(joinEventFor(player));

        verify(spectators).makeSpectator(player);
    }

    @Test
    void nobodyIsTouchedWhileTheArenaWorldItselfIsUnknown() {
        when(arena.arenaWorld()).thenReturn(Optional.empty());
        Player stranger = playerIn(arenaWorld);

        listener.onJoin(joinEventFor(stranger));

        verify(stranger, never()).setGameMode(any());
        assertThat(session.phase()).isEqualTo(GamePhase.RUNNING);
    }
}
