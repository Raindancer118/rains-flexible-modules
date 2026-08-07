package de.raindancer.modules.hungergames.listener;

import de.raindancer.modules.hungergames.service.SpectatorService;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a vanish-based spectator may not do — whether that is an eliminated tribute or a gamemaster who
 * picked "Watch without being seen" — and, just as important, what an ordinary alive tribute or a staff
 * member who is merely vanished (not one of {@link SpectatorService#isVanishSpectator}'s cases) may still
 * do without this listener getting in the way.
 */
@ExtendWith(MockitoExtension.class)
class SpectatorProtectionListenerTest {

    private SpectatorService spectators;
    private SpectatorProtectionListener listener;

    private final UUID vanishSpectator = UUID.randomUUID();
    private final UUID ordinary = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        spectators = mock(SpectatorService.class);
        org.mockito.Mockito.lenient().when(spectators.isVanishSpectator(vanishSpectator)).thenReturn(true);
        listener = new SpectatorProtectionListener(spectators);
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
        @DisplayName("is refused for a vanish spectator")
        void refusedForVanishSpectator() {
            BlockBreakEvent event = new BlockBreakEvent(mock(org.bukkit.block.Block.class),
                    playerFor(vanishSpectator));

            listener.onBreak(event);

            assertThat(event.isCancelled()).isTrue();
        }

        @Test
        @DisplayName("is left alone for an ordinary player")
        void allowedForOrdinary() {
            // The gate is isVanishSpectator, not "vanished" more broadly — a moderator vanished to
            // watch a build, who is not one of SpectatorService's own cases, must keep every one of
            // these abilities. See the class javadoc.
            BlockBreakEvent event = new BlockBreakEvent(mock(org.bukkit.block.Block.class),
                    playerFor(ordinary));

            listener.onBreak(event);

            assertThat(event.isCancelled()).isFalse();
        }
    }

    @Nested
    @DisplayName("using an item")
    class Interacting {

        @Test
        @DisplayName("is refused for a vanish spectator")
        void refusedForVanishSpectator() {
            Player player = playerFor(vanishSpectator);
            PlayerInteractEvent event = new PlayerInteractEvent(player, org.bukkit.event.block.Action.RIGHT_CLICK_AIR,
                    mock(ItemStack.class), null, null);

            listener.onInteract(event);

            assertThat(event.useItemInHand()).isEqualTo(org.bukkit.event.Event.Result.DENY);
        }

        @Test
        @DisplayName("the spectator compass itself is exempted, so its own ability can still fire")
        void compassIsExempted() {
            Player player = playerFor(vanishSpectator);
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
            Player player = playerFor(vanishSpectator);
            Location remembered = new Location(world, 1, 2, 3);
            when(spectators.lastKnownLocation(vanishSpectator)).thenReturn(Optional.of(remembered));
            PlayerRespawnEvent event = new PlayerRespawnEvent(player, new Location(world, 9, 9, 9), false);

            listener.onRespawn(event);

            assertThat(event.getRespawnLocation()).isEqualTo(remembered);
        }

        @Test
        @DisplayName("is left at vanilla's own choice when nothing is remembered")
        void leftAloneWithNothingRemembered() {
            org.bukkit.World world = mock(org.bukkit.World.class);
            Player player = playerFor(ordinary);
            Location vanillaChoice = new Location(world, 9, 9, 9);
            when(spectators.lastKnownLocation(ordinary)).thenReturn(Optional.empty());
            PlayerRespawnEvent event = new PlayerRespawnEvent(player, vanillaChoice, false);

            listener.onRespawn(event);

            assertThat(event.getRespawnLocation()).isEqualTo(vanillaChoice);
        }
    }

    @Test
    @DisplayName("what it watches, for the diagnostic that lists what is registered")
    void describesItself() {
        assertThat(listener.describe()).contains("spectator");
    }
}
