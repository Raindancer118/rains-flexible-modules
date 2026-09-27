package de.raindancer.modules.manhunt.tracker;

import de.raindancer.modules.manhunt.model.Hunt;
import de.raindancer.modules.manhunt.tracker.TrackerCompass.Point;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The door a Runner took, and the two moments a Hunter's needle has to be re-sent — a respawn and a
 * dimension change both make the server send that client a spawn position of its own, on top of the
 * compass target the sweep last sent.
 */
class TrackerListenerTest {

    private static final UUID RUNNER = UUID.nameUUIDFromBytes("runner".getBytes());
    private static final UUID HUNTER = UUID.nameUUIDFromBytes("hunter".getBytes());

    private Hunt hunt;
    private PortalMemory portals;
    private TrackerCompassService tracker;
    private TrackerListener listener;
    private World overworld;

    @BeforeEach
    void setUp() {
        hunt = Hunt.of(Set.of(RUNNER, HUNTER), Set.of(RUNNER));
        portals = new PortalMemory();
        tracker = mock(TrackerCompassService.class);
        listener = new TrackerListener(hunt, tracker, portals);
        overworld = mock(World.class);
        when(overworld.getName()).thenReturn("speedrun");
    }

    private Player playerWithId(UUID id) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        return player;
    }

    @Test
    @DisplayName("a Runner stepping into a portal leaves the door behind, in the world they left")
    void aRunnersDoorIsRemembered() {
        World nether = mock(World.class);
        when(nether.getName()).thenReturn("speedrun_nether");
        PlayerPortalEvent event = new PlayerPortalEvent(playerWithId(RUNNER),
                new Location(overworld, 100, 64, -40), new Location(nether, 12, 64, -5),
                PlayerTeleportEvent.TeleportCause.NETHER_PORTAL);

        listener.onPortal(event);

        assertThat(portals.lastCrossingIn(RUNNER, "speedrun"))
                .contains(new Point("speedrun", 100, 64, -40));
    }

    @Test
    @DisplayName("a Hunter's door is remembered too — the team compass follows a teammate down")
    void aHuntersCrossingIsRemembered() {
        World nether = mock(World.class);
        when(nether.getName()).thenReturn("speedrun_nether");
        PlayerPortalEvent event = new PlayerPortalEvent(playerWithId(HUNTER),
                new Location(overworld, 100, 64, -40), new Location(nether, 12, 64, -5),
                PlayerTeleportEvent.TeleportCause.NETHER_PORTAL);

        listener.onPortal(event);

        assertThat(portals.lastCrossingIn(HUNTER, "speedrun"))
                .contains(new Point("speedrun", 100, 64, -40));
    }

    @Test
    @DisplayName("somebody outside the hunt leaves no door behind")
    void anOutsidersCrossingIsNotRemembered() {
        UUID outsider = UUID.nameUUIDFromBytes("outsider".getBytes());
        World nether = mock(World.class);
        when(nether.getName()).thenReturn("speedrun_nether");
        listener.onPortal(new PlayerPortalEvent(playerWithId(outsider),
                new Location(overworld, 1, 64, 1), new Location(nether, 0, 64, 0),
                PlayerTeleportEvent.TeleportCause.NETHER_PORTAL));

        assertThat(portals.lastCrossingIn(outsider, "speedrun")).isEmpty();
    }

    private org.bukkit.event.player.PlayerInteractEvent rightClick(Player player) {
        org.bukkit.inventory.ItemStack compass = mock(org.bukkit.inventory.ItemStack.class);
        when(tracker.isTracker(compass)).thenReturn(true);
        return new org.bukkit.event.player.PlayerInteractEvent(player,
                org.bukkit.event.block.Action.RIGHT_CLICK_AIR, compass, null,
                org.bukkit.block.BlockFace.SELF);
    }

    @Test
    @DisplayName("a right-click cycles the compass")
    void rightClickCycles() {
        Player hunter = playerWithId(HUNTER);

        listener.onInteract(rightClick(hunter));

        verify(tracker).cycleTarget(hunter);
        verify(tracker, org.mockito.Mockito.never()).openPicker(hunter);
    }

    @Test
    @DisplayName("sneaking, the same click opens the list instead")
    void sneakRightClickOpensTheList() {
        Player hunter = playerWithId(HUNTER);
        when(hunter.isSneaking()).thenReturn(true);

        listener.onInteract(rightClick(hunter));

        verify(tracker).openPicker(hunter);
        verify(tracker, org.mockito.Mockito.never()).cycleTarget(hunter);
    }

    @Test
    @DisplayName("a respawn re-sends the needle — the server has just overwritten the client's with its own")
    void aRespawnResyncsTheNeedle() {
        Player hunter = playerWithId(HUNTER);
        listener.onRespawn(new PlayerRespawnEvent(hunter, new Location(overworld, 0, 64, 0), false));

        verify(tracker).giveOnRespawn(hunter);
        verify(tracker).resyncNeedle(HUNTER);
    }

    @Test
    @DisplayName("a dimension change re-sends it too")
    void aWorldChangeResyncsTheNeedle() {
        Player hunter = playerWithId(HUNTER);

        listener.onWorldChange(new PlayerChangedWorldEvent(hunter, overworld));

        verify(tracker).resyncNeedle(HUNTER);
    }
}
