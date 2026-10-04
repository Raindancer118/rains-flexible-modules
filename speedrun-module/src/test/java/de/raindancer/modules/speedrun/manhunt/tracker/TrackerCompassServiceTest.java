package de.raindancer.modules.speedrun.manhunt.tracker;

import de.raindancer.core.testkit.TestInventories;
import de.raindancer.core.testkit.TestItems;
import de.raindancer.core.testkit.TestPlayers;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.speedrun.manhunt.ManhuntSettings;
import de.raindancer.modules.speedrun.manhunt.tracker.TrackerCompass.Aim;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Handing a Hunter their tracking compass — reported live as "I tested with more people and did
 * still not get a compass". {@link TrackerCompassService#give} called {@code Inventory.addItem} and
 * threw the return value away: {@code addItem} does not throw when there is no room, it hands back
 * whatever would not fit — and a Hunter whose inventory happened to be full during a test session
 * (or just carrying a full loadout) was told "<gold>You have been handed a tracking compass." while
 * nothing landed anywhere.
 *
 * <p>Real stacks and a real inventory from Core's testkit: the compass {@code give()} builds is the
 * compass a server would build, and where it lands is read back, not verified on a mock.
 */
class TrackerCompassServiceTest {

    private Player hunter;
    private PlayerInventory inventory;
    private World world;
    private Location location;
    private Messages messages;
    private TrackerCompassService service;

    @BeforeEach
    void setUp() {
        hunter = TestPlayers.player("Hunter");
        inventory = hunter.getInventory();
        world = mock(World.class);
        location = mock(Location.class);
        when(hunter.getWorld()).thenReturn(world);
        when(hunter.getLocation()).thenReturn(location);

        messages = mock(Messages.class);
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("manhunt");
        when(plugin.namespace()).thenReturn("manhunt");
        // No hunt in progress: every test here is about what the service does to one item, which is
        // the half that does not need a live hunt to be right.
        service = new TrackerCompassService(plugin, java.util.Optional::empty,
                new TrackerCompass(ManhuntSettings.DEFAULTS, new PortalMemory()), new PortalMemory(),
                messages, null, ManhuntSettings.DEFAULTS);
    }

    /** One of our compasses, as the service tags it. */
    static ItemStack trackerStack() {
        return TestItems.tagged(Material.COMPASS, new NamespacedKey("manhunt", "manhunt-tracker"), "tracker");
    }

    @Test
    @DisplayName("a compass that fits goes straight into the inventory, nothing dropped")
    void fitsInInventory() {
        service.give(hunter);

        assertThat(TestInventories.stacksIn(inventory)).singleElement()
                .satisfies(stack -> assertThat(service.isTracker(stack)).isTrue());
        verify(world, never()).dropItem(any(Location.class), any(ItemStack.class));
        verify(messages).send(hunter, "manhunt.tracker.given");
    }

    @Test
    @DisplayName("a full inventory drops the compass at the Hunter's feet instead of losing it")
    void fullInventoryDropsAtFeet() {
        for (int slot = 0; slot < 36; slot++) {
            inventory.setItem(slot, TestItems.of(Material.STONE, 64));
        }
        org.bukkit.entity.Item dropped = mock(org.bukkit.entity.Item.class);
        when(world.dropItem(eq(location), any(ItemStack.class))).thenReturn(dropped);

        service.give(hunter);

        org.mockito.ArgumentCaptor<ItemStack> onTheGround = org.mockito.ArgumentCaptor.forClass(ItemStack.class);
        verify(world).dropItem(eq(location), onTheGround.capture());
        assertThat(service.isTracker(onTheGround.getValue())).isTrue();
        // Owned, so a Runner walking past cannot pick up a working tracking compass.
        verify(dropped).setOwner(hunter.getUniqueId());
        verify(messages).send(hunter, "manhunt.tracker.given");
    }

    @Test
    @DisplayName("the compass is bound — it never leaves its holder's own inventory")
    void theCompassIsBound() {
        service.give(hunter);

        ItemStack given = TestInventories.stacksIn(inventory).getFirst();
        assertThat(de.raindancer.core.content.items.BoundItems.isBound(given)).isTrue();
    }

    @Test
    @DisplayName("one on the cursor of an open window counts as carried — never a second compass")
    void onTheCursorCounts() {
        hunter.setItemOnCursor(trackerStack());

        service.give(hunter);

        assertThat(TestInventories.stacksIn(inventory)).isEmpty();
    }

    @Test
    @DisplayName("following a Runner in the overworld, the needle comes from the compass target")
    void overworldTrackingUsesTheCompassTarget() {
        // Player.setCompassTarget moves the needle without touching the item at all, so it cannot
        // redraw the compass in a hand however often the Runner moves.
        assertThat(TrackerCompassService.needleFor(Aim.Kind.TRACKING, World.Environment.NORMAL))
                .isEqualTo(TrackerCompassService.Needle.COMPASS_TARGET);
    }

    @Test
    @DisplayName("a door is pointed at by a lodestone even in the overworld — reported as a spinning needle")
    void aPortalIsAlwaysALodestone() {
        // The bug: the Runner went into the Nether, the action bar had the distance to the door, and
        // the needle spun. A compass target is the client's spawn position, which the server re-sends
        // on its own (a respawn, a dimension change) and silently overwrites. A door does not move,
        // so the item can hold it and no redraw is paid for it.
        assertThat(TrackerCompassService.needleFor(Aim.Kind.PORTAL, World.Environment.NORMAL))
                .isEqualTo(TrackerCompassService.Needle.LODESTONE);
    }

    @Test
    @DisplayName("in the Nether and the End it stays a lodestone, where a plain compass only spins")
    void otherDimensionsKeepTheLodestone() {
        assertThat(TrackerCompassService.needleFor(Aim.Kind.TRACKING, World.Environment.NETHER))
                .isEqualTo(TrackerCompassService.Needle.LODESTONE);
        assertThat(TrackerCompassService.needleFor(Aim.Kind.TRACKING, World.Environment.THE_END))
                .isEqualTo(TrackerCompassService.Needle.LODESTONE);
        assertThat(TrackerCompassService.needleFor(Aim.Kind.PORTAL, World.Environment.NETHER))
                .isEqualTo(TrackerCompassService.Needle.LODESTONE);
    }

    @Test
    @DisplayName("the distance is no longer part of the item — a lore that changes is an item that changes")
    void distanceIsNotInTheLore() {
        java.util.List<net.kyori.adventure.text.Component> lore = service.loreFor("<gray>Straight ahead.");
        String plain = lore.stream()
                .map(line -> net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(line))
                .reduce("", (a, b) -> a + "\n" + b);

        assertThat(plain).contains("Straight ahead.").doesNotContain("blocks away");
    }

    @Test
    @DisplayName("a Hunter already carrying one of ours is left alone — never a second compass")
    void alreadyCarryingIsUntouched() {
        inventory.setItem(0, trackerStack());

        service.give(hunter);

        assertThat(TestInventories.stacksIn(inventory)).hasSize(1);
        verify(messages, never()).send(hunter, "manhunt.tracker.given");
    }

    @Test
    @DisplayName("a pick from a list left open past the end of the hunt changes nothing")
    void pickAfterTheHunt() {
        java.util.UUID someone = java.util.UUID.randomUUID();
        when(hunter.getUniqueId()).thenReturn(someone);

        service.pick(hunter, TrackerCompass.Following.of(java.util.UUID.randomUUID()));
        service.cycleTarget(hunter);

        assertThat(service.pickOf(someone)).isEmpty();
        verify(messages, never()).send(any(Player.class), any(String.class), any(Object[].class));
    }

    @Test
    @DisplayName("ending a hunt while the plugin shuts down still takes every compass back")
    void disarmWhileShuttingDown() {
        // Paper refuses to schedule for a disabled plugin, and a module is disabled inside its
        // plugin's onDisable — so a hunt ended by a restart threw before anything was handed back.
        java.util.UUID id = hunter.getUniqueId();
        inventory.setItem(4, trackerStack());
        io.papermc.paper.threadedregions.scheduler.EntityScheduler refusing =
                mock(io.papermc.paper.threadedregions.scheduler.EntityScheduler.class);
        when(refusing.run(any(), any(), any())).thenThrow(
                new org.bukkit.plugin.IllegalPluginAccessException("Plugin attempted to register task while disabled"));
        when(hunter.getScheduler()).thenReturn(refusing);
        org.bukkit.Server server = mock(org.bukkit.Server.class);
        when(server.getPlayer(id)).thenReturn(hunter);
        Plugin disabled = mock(Plugin.class);
        when(disabled.getName()).thenReturn("manhunt");
        when(disabled.namespace()).thenReturn("manhunt");
        when(disabled.getServer()).thenReturn(server);
        when(disabled.isEnabled()).thenReturn(false);
        TrackerCompassService stopping = new TrackerCompassService(disabled, java.util.Optional::empty,
                new TrackerCompass(ManhuntSettings.DEFAULTS, new PortalMemory()), new PortalMemory(),
                messages, null, ManhuntSettings.DEFAULTS);

        stopping.disarm(de.raindancer.modules.speedrun.manhunt.model.Hunt.of(java.util.Set.of(id), java.util.Set.of()));

        assertThat(inventory.getItem(4)).isNull();
    }

    @Test
    @DisplayName("taking the compass off somebody also clears the distance it was showing")
    void takeFromClearsTheDistance() {
        java.util.UUID hunterId = java.util.UUID.nameUUIDFromBytes("hunter".getBytes());
        java.util.UUID runnerId = java.util.UUID.nameUUIDFromBytes("runner".getBytes());
        de.raindancer.modules.speedrun.manhunt.model.Hunt hunt = de.raindancer.modules.speedrun.manhunt.model.Hunt.of(
                java.util.Set.of(hunterId, runnerId), java.util.Set.of(runnerId));
        when(world.getName()).thenReturn("hunt");
        when(world.getEnvironment()).thenReturn(World.Environment.NORMAL);
        when(hunter.getUniqueId()).thenReturn(hunterId);
        when(hunter.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        inventory.setItem(0, trackerStack());
        inventory.setHeldItemSlot(0);
        Player runner = mock(Player.class);
        when(runner.getUniqueId()).thenReturn(runnerId);
        when(runner.isOnline()).thenReturn(true);
        when(runner.getName()).thenReturn("Runner");
        when(runner.getLocation()).thenReturn(new Location(world, 50, 64, 0));
        org.bukkit.Server server = mock(org.bukkit.Server.class);
        when(server.getPlayer(runnerId)).thenReturn(runner);
        when(server.getPlayer(hunterId)).thenReturn(hunter);
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("manhunt");
        when(plugin.namespace()).thenReturn("manhunt");
        when(plugin.getServer()).thenReturn(server);
        de.raindancer.core.ui.actionbar.ActionBars bars = mock(de.raindancer.core.ui.actionbar.ActionBars.class);
        ManhuntSettings noTrail = ManhuntSettings.DEFAULTS.withTrackerParticleTrail(false);
        TrackerCompassService live = new TrackerCompassService(plugin, () -> java.util.Optional.of(hunt),
                new TrackerCompass(noTrail, new PortalMemory()), new PortalMemory(), messages, bars, noTrail);
        live.pick(hunter, TrackerCompass.Following.of(runnerId));
        verify(bars).show(eq(hunterId), eq(TrackerCompassService.DISTANCE_OWNER), any(), any(), any());

        live.takeFrom(hunter);

        verify(bars).clear(hunterId, TrackerCompassService.DISTANCE_OWNER);
    }

    @Test
    @DisplayName("the distance switched off mid-hunt is gone at the very next redraw")
    void distanceSwitchedOffMidHunt() {
        java.util.UUID hunterId = java.util.UUID.nameUUIDFromBytes("hunter".getBytes());
        java.util.UUID runnerId = java.util.UUID.nameUUIDFromBytes("runner".getBytes());
        de.raindancer.modules.speedrun.manhunt.model.Hunt hunt = de.raindancer.modules.speedrun.manhunt.model.Hunt.of(
                java.util.Set.of(hunterId, runnerId), java.util.Set.of(runnerId));
        when(world.getName()).thenReturn("hunt");
        when(world.getEnvironment()).thenReturn(World.Environment.NORMAL);
        when(hunter.getUniqueId()).thenReturn(hunterId);
        when(hunter.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        inventory.setItem(0, trackerStack());
        inventory.setHeldItemSlot(0);
        Player runner = mock(Player.class);
        when(runner.getUniqueId()).thenReturn(runnerId);
        when(runner.getName()).thenReturn("Runner");
        when(runner.getLocation()).thenReturn(new Location(world, 50, 64, 0));
        org.bukkit.Server server = mock(org.bukkit.Server.class);
        when(server.getPlayer(runnerId)).thenReturn(runner);
        when(server.getPlayer(hunterId)).thenReturn(hunter);
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("manhunt");
        when(plugin.namespace()).thenReturn("manhunt");
        when(plugin.getServer()).thenReturn(server);
        de.raindancer.core.ui.actionbar.ActionBars bars = mock(de.raindancer.core.ui.actionbar.ActionBars.class);
        ManhuntSettings noTrail = ManhuntSettings.DEFAULTS.withTrackerParticleTrail(false);
        TrackerCompass compass = new TrackerCompass(noTrail, new PortalMemory());
        TrackerCompassService live = new TrackerCompassService(plugin, () -> java.util.Optional.of(hunt),
                compass, new PortalMemory(), messages, bars, noTrail);
        live.pick(hunter, TrackerCompass.Following.of(runnerId));

        ManhuntSettings off = noTrail.withTrackerShowDistance(false);
        compass.settings(off);
        live.settings(off);
        live.pick(hunter, TrackerCompass.Following.of(runnerId));

        verify(bars).clear(hunterId, TrackerCompassService.DISTANCE_OWNER);
    }

    @Test
    @DisplayName("refitted after a side change, somebody who no longer holds one has it taken")
    void fitTakesFromANonHolder() {
        java.util.UUID runnerId = java.util.UUID.nameUUIDFromBytes("runner".getBytes());
        java.util.UUID hunterId = java.util.UUID.nameUUIDFromBytes("hunter".getBytes());
        de.raindancer.modules.speedrun.manhunt.model.Hunt hunt = de.raindancer.modules.speedrun.manhunt.model.Hunt.of(
                java.util.Set.of(hunterId, runnerId), java.util.Set.of(runnerId));
        when(hunter.getUniqueId()).thenReturn(runnerId);
        inventory.setItem(2, trackerStack());

        service.fit(hunt, hunter);

        assertThat(inventory.getItem(2)).isNull();
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("picking from the list")
    class Picking {

        private final java.util.UUID runner = java.util.UUID.nameUUIDFromBytes("runner".getBytes());
        private final java.util.UUID hunterId = java.util.UUID.nameUUIDFromBytes("hunter".getBytes());
        private final java.util.UUID mate = java.util.UUID.nameUUIDFromBytes("mate".getBytes());
        private org.bukkit.Server server;

        private TrackerCompassService serviceWith(ManhuntSettings settings) {
            de.raindancer.modules.speedrun.manhunt.model.Hunt hunt = de.raindancer.modules.speedrun.manhunt.model.Hunt.of(
                    java.util.Set.of(runner, hunterId, mate), java.util.Set.of(runner));
            Plugin plugin = mock(Plugin.class);
            when(plugin.getName()).thenReturn("manhunt");
            when(plugin.namespace()).thenReturn("manhunt");
            server = mock(org.bukkit.Server.class);
            when(plugin.getServer()).thenReturn(server);
            when(hunter.getUniqueId()).thenReturn(hunterId);
            when(hunter.isOnline()).thenReturn(true);
            when(hunter.getName()).thenReturn("Hunter");
            when(server.getPlayer(hunterId)).thenReturn(hunter);
            online(runner, "Runner");
            online(mate, "Mate");
            return new TrackerCompassService(plugin, () -> java.util.Optional.of(hunt),
                    new TrackerCompass(settings, new PortalMemory()), new PortalMemory(), messages,
                    null, settings);
        }

        private void online(java.util.UUID id, String name) {
            Player player = mock(Player.class);
            when(player.getUniqueId()).thenReturn(id);
            when(player.isOnline()).thenReturn(true);
            when(player.getName()).thenReturn(name);
            when(player.getLocation()).thenReturn(location);
            when(server.getPlayer(id)).thenReturn(player);
        }

        @Test
        @DisplayName("the list is the nearest and every Runner — teammates are the team compass' business")
        void targets() {
            assertThat(serviceWith(ManhuntSettings.DEFAULTS.withTrackerTeamCompass(true)).targetsFor(hunter))
                    .extracting(TrackerCompassService.Target::name)
                    .containsExactly("Whoever is nearest", "Runner");
        }

        @Test
        @DisplayName("a teammate cannot be picked on the tracking compass")
        void noTeammate() {
            TrackerCompassService picking = serviceWith(ManhuntSettings.DEFAULTS.withTrackerTeamCompass(true));

            picking.pick(hunter, TrackerCompass.Following.of(mate));

            assertThat(picking.pickOf(hunterId)).isEmpty();
        }

        @Test
        @DisplayName("where the owner aims the compass, the list refuses like a right-click does")
        void refusedWhenPickingIsOff() {
            TrackerCompassService picking = serviceWith(ManhuntSettings.DEFAULTS.withTrackerHunterMayChoose(false));

            picking.openPicker(hunter);
            picking.pick(hunter, TrackerCompass.Following.of(runner));

            assertThat(picking.pickOf(hunterId)).isEmpty();
            org.mockito.Mockito.verify(messages, org.mockito.Mockito.times(2))
                    .send(hunter, "manhunt.tracker.picking-off");
        }

        @Test
        @DisplayName("sneak-click opens whatever screen the module wired")
        void opensTheScreen() {
            TrackerCompassService picking = serviceWith(ManhuntSettings.DEFAULTS);
            java.util.List<Player> opened = new java.util.ArrayList<>();
            picking.pickerScreen(opened::add);

            picking.openPicker(hunter);

            assertThat(opened).containsExactly(hunter);
        }
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("the Runners' compass")
    class RunnersCompass {

        private final java.util.UUID runner = java.util.UUID.nameUUIDFromBytes("r".getBytes());
        private final java.util.UUID otherRunner = java.util.UUID.nameUUIDFromBytes("r2".getBytes());
        private final java.util.UUID hunterA = java.util.UUID.nameUUIDFromBytes("ha".getBytes());
        private final java.util.UUID hunterB = java.util.UUID.nameUUIDFromBytes("hb".getBytes());
        private final org.bukkit.Server server = mock(org.bukkit.Server.class);
        private de.raindancer.modules.speedrun.manhunt.model.Hunt hunt;

        private Player online(java.util.UUID id, String name) {
            Player player = mock(Player.class);
            when(player.getUniqueId()).thenReturn(id);
            when(player.isOnline()).thenReturn(true);
            when(player.getName()).thenReturn(name);
            when(player.getLocation()).thenReturn(location);
            when(player.getInventory()).thenReturn(inventory);
            when(server.getPlayer(id)).thenReturn(player);
            return player;
        }

        private TrackerCompassService serviceWith(ManhuntSettings settings) {
            hunt = de.raindancer.modules.speedrun.manhunt.model.Hunt.of(
                    java.util.Set.of(runner, otherRunner, hunterA, hunterB), java.util.Set.of(runner, otherRunner));
            Plugin plugin = mock(Plugin.class);
            when(plugin.getName()).thenReturn("manhunt");
            when(plugin.namespace()).thenReturn("manhunt");
            when(plugin.getServer()).thenReturn(server);
            online(runner, "Runner");
            online(otherRunner, "Other");
            online(hunterA, "HunterA");
            online(hunterB, "HunterB");
            return new TrackerCompassService(plugin, () -> java.util.Optional.of(hunt),
                    new TrackerCompass(settings, new PortalMemory()), new PortalMemory(), messages, null, settings);
        }

        @Test
        @DisplayName("off by default: a Runner holds nothing and can pick nothing")
        void offByDefault() {
            TrackerCompassService service = serviceWith(ManhuntSettings.DEFAULTS);

            assertThat(service.isHolder(hunt, runner)).isFalse();
            assertThat(service.targetsFor(server.getPlayer(runner))).isEmpty();
            assertThat(service.isHolder(hunt, hunterA)).isTrue();
        }

        @Test
        @DisplayName("on: a Runner tracks the Hunters — the nearest, or one they pick — never another Runner")
        void runnersTrackHunters() {
            TrackerCompassService service = serviceWith(ManhuntSettings.DEFAULTS.withRunnerCompass(true));
            Player me = server.getPlayer(runner);

            assertThat(service.isHolder(hunt, runner)).isTrue();
            assertThat(service.targetsFor(me)).extracting(TrackerCompassService.Target::name)
                    .containsExactlyInAnyOrder("Whoever is nearest", "HunterA", "HunterB");

            service.pick(me, TrackerCompass.Following.of(otherRunner));
            assertThat(service.pickOf(runner)).isEmpty();
            service.pick(me, TrackerCompass.Following.of(hunterB));
            assertThat(service.pickOf(runner)).contains(TrackerCompass.Following.of(hunterB));
        }

        @Test
        @DisplayName("somebody in spectator mode is nobody's target — creative still is")
        void spectatorsAreNoTargets() {
            TrackerCompassService service = serviceWith(ManhuntSettings.DEFAULTS.withRunnerCompass(true));
            when(server.getPlayer(hunterA).getGameMode()).thenReturn(org.bukkit.GameMode.SPECTATOR);
            when(server.getPlayer(hunterB).getGameMode()).thenReturn(org.bukkit.GameMode.CREATIVE);

            assertThat(service.targetsFor(server.getPlayer(runner))).extracting(TrackerCompassService.Target::name)
                    .containsExactlyInAnyOrder("Whoever is nearest", "HunterB");
        }

        @Test
        @DisplayName("a caught Runner holds no compass any more")
        void caughtRunner() {
            TrackerCompassService service = serviceWith(ManhuntSettings.DEFAULTS.withRunnerCompass(true));
            hunt.eliminate(runner);

            assertThat(service.isHolder(hunt, runner)).isFalse();
        }
    }
}
