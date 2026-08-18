package de.raindancer.modules.speedrun;

import de.raindancer.core.ui.messages.Messages;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpeedrunLobbyListenerTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());

    private SpeedrunLobby lobby;
    private SpeedrunLobbyItems items;
    private Messages messages;
    private SpeedrunLobbyListener listener;

    @BeforeEach
    void setUp() {
        lobby = mock(SpeedrunLobby.class);
        items = mock(SpeedrunLobbyItems.class);
        messages = mock(Messages.class);
        listener = new SpeedrunLobbyListener(mock(Plugin.class), lobby, items, null, messages);
    }

    private Player playerWithId(UUID id) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        return player;
    }

    /**
     * Makes a mocked player's own scheduler run what it is handed, straight away — the lobby sweep
     * hops onto each player's thread through {@code Scheduling.entity} so it is Folia-safe, and
     * without this the task would be dropped into a mock and never run.
     */
    private static void runsItsOwnTasksImmediately(Player player) {
        EntityScheduler scheduler = mock(EntityScheduler.class);
        when(scheduler.run(any(), any(), any())).thenAnswer(invocation -> {
            invocation.getArgument(1, Consumer.class).accept(null);
            return null;
        });
        when(player.getScheduler()).thenReturn(scheduler);
    }

    @Nested
    @DisplayName("join")
    class Join {

        private Player playerInWorld(String worldName) {
            Player player = playerWithId(ALICE);
            World world = mock(World.class);
            when(world.getName()).thenReturn(worldName);
            when(player.getWorld()).thenReturn(world);
            return player;
        }

        @Test
        @DisplayName("gives the kit while the lobby is READY and the player is in the lobby world")
        void givesKitWhenReadyInLobbyWorld() {
            when(lobby.state()).thenReturn(SpeedrunLobbyState.READY);
            when(lobby.config()).thenReturn(
                    new SpeedrunSettings("world", "minecraft:end/kill_dragon", SpeedrunDeathPolicy.OFF, false, 100, 0, 100, 0, false, 0, 0, 0, 0, 0));
            Player player = playerInWorld("world");

            listener.onJoin(new PlayerJoinEvent(player, "hi"));

            verify(items).give(player);
        }

        /**
         * The actual incident this guards: with no world check at all, the lobby being READY — which
         * is true almost all the time on a shared server — meant every join anywhere on the server was
         * cleared and handed the two lobby items, regardless of which world the player actually spawned
         * into. Real gear was lost this way before this check existed.
         */
        @Test
        @DisplayName("does NOT touch a player joining into a different world, even while READY — "
                + "it sends them to the lobby world instead")
        void leavesInventoryAloneOutsideTheLobbyWorld() {
            when(lobby.state()).thenReturn(SpeedrunLobbyState.READY);
            when(lobby.config()).thenReturn(
                    new SpeedrunSettings("speedrun-lobby", "minecraft:end/kill_dragon",
                            SpeedrunDeathPolicy.OFF, false, 100, 0, 100, 0, false, 0, 0, 0, 0, 0));
            Player player = playerInWorld("world");   // the server's real, shared world — not the lobby

            try (MockedStatic<Bukkit> bukkit =
                         mockStatic(Bukkit.class)) {
                // Not loaded, in this test — the point is only that nothing here ever touches the
                // player's own inventory outside the lobby world, whether or not a teleport follows.
                bukkit.when(() -> Bukkit.getWorld("speedrun-lobby")).thenReturn(null);

                listener.onJoin(new PlayerJoinEvent(player, "hi"));
            }

            verify(items, never()).give(any());
        }

        @Test
        @DisplayName("leaves the inventory alone while a run is in progress")
        void leavesInventoryAloneWhileRunning() {
            when(lobby.state()).thenReturn(SpeedrunLobbyState.RUNNING);
            when(lobby.config()).thenReturn(
                    new SpeedrunSettings("world", "minecraft:end/kill_dragon", SpeedrunDeathPolicy.OFF,
                            false, 100, 0, 100, 0, false, 0, 0, 0, 0, 0));
            Player player = playerInWorld("world");   // already in the lobby world — no teleport needed

            listener.onJoin(new PlayerJoinEvent(player, "hi"));

            verify(items, never()).give(any());
        }

        @Test
        @DisplayName("leaves the inventory alone while a finished run waits to reset")
        void leavesInventoryAloneWhileFinished() {
            when(lobby.state()).thenReturn(SpeedrunLobbyState.FINISHED);
            when(lobby.config()).thenReturn(
                    new SpeedrunSettings("world", "minecraft:end/kill_dragon", SpeedrunDeathPolicy.OFF,
                            false, 100, 0, 100, 0, false, 0, 0, 0, 0, 0));
            Player player = playerInWorld("world");   // already in the lobby world — no teleport needed

            listener.onJoin(new PlayerJoinEvent(player, "hi"));

            verify(items, never()).give(any());
        }

        @Test
        @DisplayName("teleports a player joining outside the lobby world straight to it")
        void teleportsToTheLobbyWorldOnJoin() {
            when(lobby.state()).thenReturn(SpeedrunLobbyState.RUNNING);
            when(lobby.config()).thenReturn(
                    new SpeedrunSettings("speedrun", "minecraft:end/kill_dragon", SpeedrunDeathPolicy.OFF,
                            false, 100, 0, 100, 0, false, 0, 0, 0, 0, 0));
            Player player = playerInWorld("world");
            World lobbyWorld = mock(World.class);
            Location lobbySpawn = mock(Location.class);
            when(lobbyWorld.getSpawnLocation()).thenReturn(lobbySpawn);

            try (MockedStatic<Bukkit> bukkit =
                         mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getWorld("speedrun")).thenReturn(lobbyWorld);

                listener.onJoin(new PlayerJoinEvent(player, "hi"));
            }

            verify(player).teleportAsync(lobbySpawn);
        }

        @Test
        @DisplayName("does not try to teleport when the lobby world is not loaded")
        void doesNotTeleportWhenLobbyWorldMissing() {
            when(lobby.state()).thenReturn(SpeedrunLobbyState.READY);
            when(lobby.config()).thenReturn(
                    new SpeedrunSettings("speedrun", "minecraft:end/kill_dragon", SpeedrunDeathPolicy.OFF,
                            false, 100, 0, 100, 0, false, 0, 0, 0, 0, 0));
            Player player = playerInWorld("world");

            try (MockedStatic<Bukkit> bukkit =
                         mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getWorld("speedrun")).thenReturn(null);

                listener.onJoin(new PlayerJoinEvent(player, "hi"));
            }

            verify(player, never()).teleportAsync(any(Location.class));
        }
    }

    @Nested
    @DisplayName("world change")
    class WorldChange {

        @Test
        @DisplayName("gives the kit once a player lands in the lobby world while READY")
        void givesKitOnArrivalWhileReady() {
            when(lobby.state()).thenReturn(SpeedrunLobbyState.READY);
            when(lobby.config()).thenReturn(
                    new SpeedrunSettings("speedrun", "minecraft:end/kill_dragon", SpeedrunDeathPolicy.OFF,
                            false, 100, 0, 100, 0, false, 0, 0, 0, 0, 0));
            Player player = playerWithId(ALICE);
            World lobbyWorld = mock(World.class);
            when(lobbyWorld.getName()).thenReturn("speedrun");
            when(player.getWorld()).thenReturn(lobbyWorld);
            World from = mock(World.class);

            listener.onWorldChange(new PlayerChangedWorldEvent(player, from));

            verify(items).give(player);
        }

        @Test
        @DisplayName("does nothing while a run is under way")
        void doesNothingWhileRunning() {
            when(lobby.state()).thenReturn(SpeedrunLobbyState.RUNNING);
            Player player = playerWithId(ALICE);
            World from = mock(World.class);

            listener.onWorldChange(new PlayerChangedWorldEvent(player, from));

            verify(items, never()).give(any());
        }
    }

    @Nested
    @DisplayName("giveItemsToEveryoneInLobby — the onReady hook's own target")
    class SweepingTheLobby {

        @Test
        @DisplayName("hands the items to everybody already standing in the lobby world")
        void givesItemsToEverybodyPresent() {
            when(lobby.state()).thenReturn(SpeedrunLobbyState.READY);
            when(lobby.config()).thenReturn(
                    new SpeedrunSettings("speedrun", "minecraft:end/kill_dragon", SpeedrunDeathPolicy.OFF,
                            false, 100, 0, 100, 0, false, 0, 0, 0, 0, 0));
            World lobbyWorld = mock(World.class);
            when(lobbyWorld.getName()).thenReturn("speedrun");
            Player alice = playerWithId(ALICE);
            when(alice.getWorld()).thenReturn(lobbyWorld);
            runsItsOwnTasksImmediately(alice);
            Player bob = playerWithId(UUID.nameUUIDFromBytes("bob".getBytes()));
            when(bob.getWorld()).thenReturn(lobbyWorld);
            runsItsOwnTasksImmediately(bob);
            when(lobbyWorld.getPlayers()).thenReturn(List.of(alice, bob));

            try (MockedStatic<Bukkit> bukkit =
                         mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getWorld("speedrun")).thenReturn(lobbyWorld);

                listener.giveItemsToEveryoneInLobby();
            }

            verify(items).give(alice);
            verify(items).give(bob);
        }

        @Test
        @DisplayName("does nothing when the lobby world is not loaded")
        void doesNothingWhenWorldMissing() {
            when(lobby.config()).thenReturn(
                    new SpeedrunSettings("speedrun", "minecraft:end/kill_dragon", SpeedrunDeathPolicy.OFF,
                            false, 100, 0, 100, 0, false, 0, 0, 0, 0, 0));

            try (MockedStatic<Bukkit> bukkit =
                         mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getWorld("speedrun")).thenReturn(null);

                assertThatCode(() -> listener.giveItemsToEveryoneInLobby()).doesNotThrowAnyException();
            }

            verify(items, never()).give(any());
        }
    }

    @Test
    @DisplayName("quit always asks the lobby whether it should reset")
    void quitAsksLobby() {
        Player player = playerWithId(ALICE);

        listener.onQuit(new PlayerQuitEvent(player, "bye"));

        verify(lobby).resetIfAbandoned(ALICE);
    }

    @Nested
    @DisplayName("moving before a run exists")
    class Movement {

        private Player playerInWorld(String worldName) {
            Player player = playerWithId(ALICE);
            World world = mock(World.class);
            when(world.getName()).thenReturn(worldName);
            when(player.getWorld()).thenReturn(world);
            return player;
        }

        @Test
        @DisplayName("cancels an actual step in the lobby world while READY")
        void cancelsStepsWhileReady() {
            when(lobby.state()).thenReturn(SpeedrunLobbyState.READY);
            when(lobby.config()).thenReturn(
                    new SpeedrunSettings("world", "minecraft:end/kill_dragon", SpeedrunDeathPolicy.OFF, false, 100, 0, 100, 0, false, 0, 0, 0, 0, 0));
            Player player = playerInWorld("world");
            World world = player.getWorld();
            Location from = new Location(world, 10, 64, 10, 90f, 0f);
            Location walked = new Location(world, 11, 64, 10);

            org.bukkit.event.player.PlayerMoveEvent event =
                    new org.bukkit.event.player.PlayerMoveEvent(player, from, walked);
            listener.onMove(event);

            assertThat(event.isCancelled()).isTrue();
        }

        @Test
        @DisplayName("does not cancel just looking around")
        void allowsLookingAround() {
            when(lobby.state()).thenReturn(SpeedrunLobbyState.READY);
            when(lobby.config()).thenReturn(
                    new SpeedrunSettings("world", "minecraft:end/kill_dragon", SpeedrunDeathPolicy.OFF, false, 100, 0, 100, 0, false, 0, 0, 0, 0, 0));
            Player player = playerInWorld("world");
            World world = player.getWorld();
            Location from = new Location(world, 10, 64, 10, 90f, 0f);
            Location lookedAround = new Location(world, 10, 64, 10, 180f, 0f);

            org.bukkit.event.player.PlayerMoveEvent event =
                    new org.bukkit.event.player.PlayerMoveEvent(player, from, lookedAround);
            listener.onMove(event);

            assertThat(event.isCancelled()).isFalse();
        }

        @Test
        @DisplayName("leaves movement alone once a run is under way")
        void allowsMovementOnceRunning() {
            when(lobby.state()).thenReturn(SpeedrunLobbyState.RUNNING);
            Player player = playerInWorld("world");
            World world = player.getWorld();
            Location from = new Location(world, 10, 64, 10);
            Location walked = new Location(world, 11, 64, 10);

            org.bukkit.event.player.PlayerMoveEvent event =
                    new org.bukkit.event.player.PlayerMoveEvent(player, from, walked);
            listener.onMove(event);

            assertThat(event.isCancelled()).isFalse();
        }

        @Test
        @DisplayName("leaves movement alone outside the lobby world, even while READY")
        void allowsMovementOutsideTheLobbyWorld() {
            when(lobby.state()).thenReturn(SpeedrunLobbyState.READY);
            when(lobby.config()).thenReturn(
                    new SpeedrunSettings("speedrun-lobby", "minecraft:end/kill_dragon",
                            SpeedrunDeathPolicy.OFF, false, 100, 0, 100, 0, false, 0, 0, 0, 0, 0));
            Player player = playerInWorld("world");
            World world = player.getWorld();
            Location from = new Location(world, 10, 64, 10);
            Location walked = new Location(world, 11, 64, 10);

            org.bukkit.event.player.PlayerMoveEvent event =
                    new org.bukkit.event.player.PlayerMoveEvent(player, from, walked);
            listener.onMove(event);

            assertThat(event.isCancelled()).isFalse();
        }
    }

    @Nested
    @DisplayName("clicking the start block")
    class StartClick {

        private Player clicker;
        private World lobbyWorld;
        private ItemStack startBlock;

        @BeforeEach
        void setUp() {
            clicker = playerWithId(ALICE);
            lobbyWorld = mock(World.class);
            when(lobbyWorld.getName()).thenReturn("world");
            when(clicker.getWorld()).thenReturn(lobbyWorld);
            PlayerInventory inv = mock(PlayerInventory.class);
            when(clicker.getInventory()).thenReturn(inv);

            startBlock = mock(ItemStack.class);
            when(items.isStart(startBlock)).thenReturn(true);
            when(items.isMenu(startBlock)).thenReturn(false);

            SpeedrunSettings config = new SpeedrunSettings("world", "minecraft:end/kill_dragon",
                    SpeedrunDeathPolicy.OFF, false, 100, 0, 100, 0, false, 0, 0, 0, 0, 0);
            when(lobby.config()).thenReturn(config);
        }

        @Test
        @DisplayName("does nothing when the block is used outside the lobby world, and says so")
        void ignoredOutsideLobbyWorld() {
            when(lobbyWorld.getName()).thenReturn("somewhere-else");
            PlayerInteractEvent event = new PlayerInteractEvent(clicker, Action.RIGHT_CLICK_BLOCK,
                    startBlock, null, null, EquipmentSlot.HAND);

            listener.onInteract(event);

            verify(lobby, never()).beginCountdown(any());
            verify(messages).send(clicker, "speedrun.start.wrong-world", "world", "world");
        }

        @Test
        @DisplayName("begins the countdown with everybody currently in the lobby world, and clears their inventories")
        void startsAndClearsInventoriesOnSuccess() {
            Player other = playerWithId(UUID.nameUUIDFromBytes("bob".getBytes()));
            PlayerInventory otherInv = mock(PlayerInventory.class);
            when(other.getInventory()).thenReturn(otherInv);
            when(lobbyWorld.getPlayers()).thenReturn(List.of(clicker, other));
            when(lobby.beginCountdown(any())).thenReturn(SpeedrunLobby.StartOutcome.STARTED);

            PlayerInteractEvent event = new PlayerInteractEvent(clicker, Action.RIGHT_CLICK_BLOCK,
                    startBlock, null, null, EquipmentSlot.HAND);
            listener.onInteract(event);

            assertThat(event.useItemInHand()).isEqualTo(org.bukkit.event.Event.Result.DENY);
            verify(lobby).beginCountdown(java.util.Set.of(ALICE, other.getUniqueId()));
            verify(clicker.getInventory()).clear();
            verify(other.getInventory()).clear();
        }

        @Test
        @DisplayName("a refused start leaves inventories untouched, and tells the clicker why")
        void refusedStartLeavesInventoriesAlone() {
            when(lobbyWorld.getPlayers()).thenReturn(List.of(clicker));
            when(lobby.beginCountdown(any())).thenReturn(SpeedrunLobby.StartOutcome.NO_END_CONDITION);

            PlayerInteractEvent event = new PlayerInteractEvent(clicker, Action.RIGHT_CLICK_BLOCK,
                    startBlock, null, null, EquipmentSlot.HAND);
            listener.onInteract(event);

            assertThat(event.useItemInHand()).isEqualTo(org.bukkit.event.Event.Result.DENY);
            verify(clicker.getInventory(), never()).clear();
            verify(messages).send(clicker, "speedrun.start.no-end-condition");
        }
    }

    @Nested
    @DisplayName("clicks that are not this listener's business")
    class Ignored {

        @Test
        @DisplayName("the off hand's copy of the same click is ignored")
        void offHandIgnored() {
            Player player = playerWithId(ALICE);
            ItemStack held = mock(ItemStack.class);
            PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, held,
                    null, null, EquipmentSlot.OFF_HAND);

            listener.onInteract(event);

            verify(items, never()).isMenu(any());
            verify(items, never()).isStart(any());
        }

        @Test
        @DisplayName("a left click is not a menu or start action")
        void leftClickIgnored() {
            Player player = playerWithId(ALICE);
            ItemStack held = mock(ItemStack.class);
            PlayerInteractEvent event = new PlayerInteractEvent(player, Action.LEFT_CLICK_AIR, held,
                    null, null, EquipmentSlot.HAND);

            listener.onInteract(event);

            verify(items, never()).isMenu(any());
        }

        @Test
        @DisplayName("holding neither tagged item does nothing")
        void unrelatedItemIgnored() {
            Player player = playerWithId(ALICE);
            ItemStack held = mock(ItemStack.class);
            PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, held,
                    null, null, EquipmentSlot.HAND);

            listener.onInteract(event);

            // isCancelled() itself is not a reliable signal here: with no clicked block the event
            // already reads as cancelled by Bukkit's own default (useInteractedBlock() == DENY for a
            // null block) before this listener ever runs. useItemInHand() is what setCancelled(true)
            // actually touches, so it is the precise check that this listener did nothing.
            assertThat(event.useItemInHand()).isEqualTo(org.bukkit.event.Event.Result.DEFAULT);
        }
    }
}
