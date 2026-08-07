package de.raindancer.modules.hungergames.listener;

import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.rules.TeamRules;
import de.raindancer.modules.hungergames.service.SpectatorService;
import de.raindancer.modules.hungergames.store.GameSession;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What an eliminated tribute may not do — and, just as important, what an ordinary alive tribute or a
 * staff member who happens to be vanished may still do without this listener getting in the way.
 */
@ExtendWith(MockitoExtension.class)
class SpectatorProtectionListenerTest {

    private GameSession session;
    private SpectatorService spectators;
    private SpectatorProtectionListener listener;

    private final UUID eliminated = UUID.randomUUID();
    private final UUID alive = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        session = new GameSession(TeamRules::defaults, new de.raindancer.modules.hungergames.RecordingGameEvents(),
                new de.raindancer.modules.hungergames.InMemorySessionStore(), () -> 0L, new Random(1));
        session.whitelistAdd(eliminated, "Eliminated");
        session.whitelistAdd(alive, "Alive");
        session.transitionTo(GamePhase.PREFLIGHT);
        session.transitionTo(GamePhase.LOBBY);
        session.transitionTo(GamePhase.STARTUP);
        session.transitionTo(GamePhase.READY);
        session.transitionTo(GamePhase.RUNNING);
        session.eliminate(eliminated, null);

        spectators = mock(SpectatorService.class);
        listener = new SpectatorProtectionListener(session, spectators);
    }

    private Player playerFor(UUID uuid) {
        Player player = mock(Player.class);
        org.mockito.Mockito.lenient().when(player.getUniqueId()).thenReturn(uuid);
        return player;
    }

    @Nested
    @DisplayName("breaking a block")
    class Breaking {

        @Test
        @DisplayName("is refused for an eliminated tribute")
        void refusedForEliminated() {
            BlockBreakEvent event = new BlockBreakEvent(mock(org.bukkit.block.Block.class),
                    playerFor(eliminated));

            listener.onBreak(event);

            assertThat(event.isCancelled()).isTrue();
        }

        @Test
        @DisplayName("is left alone for a tribute who is still alive")
        void allowedForAlive() {
            BlockBreakEvent event = new BlockBreakEvent(mock(org.bukkit.block.Block.class), playerFor(alive));

            listener.onBreak(event);

            assertThat(event.isCancelled()).isFalse();
        }

        @Test
        @DisplayName("is left alone for somebody who is not a tribute of this round at all")
        void allowedForAStranger() {
            // The gate is "eliminated tribute", not "vanished" — a moderator who vanished to watch a
            // build must keep every one of these abilities. See the class javadoc.
            BlockBreakEvent event = new BlockBreakEvent(mock(org.bukkit.block.Block.class),
                    playerFor(stranger));

            listener.onBreak(event);

            assertThat(event.isCancelled()).isFalse();
        }
    }

    @Nested
    @DisplayName("using an item")
    class Interacting {

        @Test
        @DisplayName("is refused for an eliminated tribute")
        void refusedForEliminated() {
            Player player = playerFor(eliminated);
            PlayerInteractEvent event = new PlayerInteractEvent(player, org.bukkit.event.block.Action.RIGHT_CLICK_AIR,
                    mock(ItemStack.class), null, null);

            listener.onInteract(event);

            assertThat(event.useItemInHand()).isEqualTo(org.bukkit.event.Event.Result.DENY);
        }

        @Test
        @DisplayName("the spectator compass itself is exempted, so its own ability can still fire")
        void compassIsExempted() {
            Player player = playerFor(eliminated);
            ItemStack compass = mock(ItemStack.class);
            when(spectators.isTheSpectatorCompass(compass)).thenReturn(true);
            PlayerInteractEvent event = new PlayerInteractEvent(player, org.bukkit.event.block.Action.RIGHT_CLICK_AIR,
                    compass, null, null);

            listener.onInteract(event);

            assertThat(event.useItemInHand())
                    .as("cancelling this would take Core's own dispatch of the compass's ability with it")
                    .isNotEqualTo(org.bukkit.event.Event.Result.DENY);
        }
    }

    @Nested
    @DisplayName("respawning")
    class Respawning {

        @Test
        @DisplayName("is moved to where they last stood, when that is remembered")
        void movedToLastKnownLocation() {
            org.bukkit.World world = mock(org.bukkit.World.class);
            Player player = playerFor(eliminated);
            Location remembered = new Location(world, 1, 2, 3);
            when(spectators.lastKnownLocation(eliminated)).thenReturn(Optional.of(remembered));
            PlayerRespawnEvent event = new PlayerRespawnEvent(player, new Location(world, 9, 9, 9), false);

            listener.onRespawn(event);

            assertThat(event.getRespawnLocation()).isEqualTo(remembered);
        }

        @Test
        @DisplayName("is left at vanilla's own choice when nothing is remembered")
        void leftAloneWithNothingRemembered() {
            org.bukkit.World world = mock(org.bukkit.World.class);
            Player player = playerFor(alive);
            Location vanillaChoice = new Location(world, 9, 9, 9);
            when(spectators.lastKnownLocation(alive)).thenReturn(Optional.empty());
            PlayerRespawnEvent event = new PlayerRespawnEvent(player, vanillaChoice, false);

            listener.onRespawn(event);

            assertThat(event.getRespawnLocation()).isEqualTo(vanillaChoice);
        }
    }

    @Test
    @DisplayName("what it watches, for the diagnostic that lists what is registered")
    void describesItself() {
        assertThat(listener.describe()).contains("eliminated");
    }
}
