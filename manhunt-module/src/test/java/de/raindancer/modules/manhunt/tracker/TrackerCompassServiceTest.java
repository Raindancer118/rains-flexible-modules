package de.raindancer.modules.manhunt.tracker;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.manhunt.ManhuntSettings;
import de.raindancer.modules.manhunt.tracker.TrackerCompass.Aim;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

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
 * <h2>Why {@code place()}, not {@code give()}, for the inventory-behaviour tests</h2>
 * {@code give()} builds a real {@code Material.COMPASS} {@code ItemStack}, which lazily resolves
 * {@code io.papermc.paper.registry.RegistryAccess} — not available outside a running Paper server.
 * No test anywhere else in this reactor constructs a real {@code ItemStack} for the same reason
 * (see {@code MannequinEquipServiceTest}'s own note). {@link TrackerCompassService#place} is exactly
 * {@code give()}'s fix, split out so the decision — inventory first, feet if it does not fit, the
 * message either way — is reachable with a mocked {@code ItemStack} instead.
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
        hunter = mock(Player.class);
        inventory = mock(PlayerInventory.class);
        world = mock(World.class);
        location = mock(Location.class);
        when(hunter.getInventory()).thenReturn(inventory);
        when(hunter.getWorld()).thenReturn(world);
        when(hunter.getLocation()).thenReturn(location);
        // No tracker already carried — findTracker() sees an empty inventory.
        when(inventory.getContents()).thenReturn(new ItemStack[36]);

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

    @Test
    @DisplayName("a compass that fits goes straight into the inventory, nothing dropped")
    void fitsInInventory() {
        ItemStack compass = mock(ItemStack.class);
        when(inventory.addItem(compass)).thenReturn(new HashMap<>());

        service.place(hunter, compass);

        verify(world, never()).dropItem(any(Location.class), any(ItemStack.class));
        verify(messages).send(hunter, "manhunt.tracker.given");
    }

    @Test
    @DisplayName("a full inventory drops the compass at the Hunter's feet instead of losing it")
    void fullInventoryDropsAtFeet() {
        ItemStack compass = mock(ItemStack.class);
        HashMap<Integer, ItemStack> notFitted = new HashMap<>();
        notFitted.put(0, compass);
        when(inventory.addItem(compass)).thenReturn(notFitted);

        service.place(hunter, compass);

        verify(world).dropItem(location, compass);
        verify(messages).send(hunter, "manhunt.tracker.given");
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
        ItemStack existing = mock(ItemStack.class);
        org.bukkit.inventory.meta.CompassMeta meta = mock(org.bukkit.inventory.meta.CompassMeta.class);
        when(existing.getType()).thenReturn(org.bukkit.Material.COMPASS);
        when(existing.hasItemMeta()).thenReturn(true);
        when(existing.getItemMeta()).thenReturn(meta);
        org.bukkit.persistence.PersistentDataContainer pdc =
                mock(org.bukkit.persistence.PersistentDataContainer.class);
        when(existing.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(any(), any())).thenReturn("tracker");
        ItemStack[] contents = new ItemStack[36];
        contents[0] = existing;
        when(inventory.getContents()).thenReturn(contents);

        service.give(hunter);

        verify(inventory, never()).addItem(any(ItemStack.class));
    }

    /** One of our compasses, as a mock the service recognises by its tag. */
    static ItemStack trackerStack(org.bukkit.inventory.meta.CompassMeta meta) {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(org.bukkit.Material.COMPASS);
        when(stack.hasItemMeta()).thenReturn(true);
        when(stack.getItemMeta()).thenReturn(meta);
        org.bukkit.persistence.PersistentDataContainer pdc =
                mock(org.bukkit.persistence.PersistentDataContainer.class);
        when(stack.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(any(), any())).thenReturn("tracker");
        return stack;
    }

    @Test
    @DisplayName("ending a hunt while the plugin shuts down still takes every compass back")
    void disarmWhileShuttingDown() {
        // Paper refuses to schedule for a disabled plugin, and a module is disabled inside its
        // plugin's onDisable — so a hunt ended by a restart threw before anything was handed back.
        java.util.UUID id = java.util.UUID.nameUUIDFromBytes("h".getBytes());
        when(hunter.getUniqueId()).thenReturn(id);
        ItemStack[] contents = new ItemStack[36];
        contents[4] = trackerStack(mock(org.bukkit.inventory.meta.CompassMeta.class));
        when(inventory.getContents()).thenReturn(contents);
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

        stopping.disarm(de.raindancer.modules.manhunt.model.Hunt.of(java.util.Set.of(id), java.util.Set.of()));

        verify(inventory).setItem(4, null);
    }

    @Test
    @DisplayName("taking the compass off somebody also clears the distance it was showing")
    void takeFromClearsTheDistance() {
        java.util.UUID hunterId = java.util.UUID.nameUUIDFromBytes("hunter".getBytes());
        java.util.UUID runnerId = java.util.UUID.nameUUIDFromBytes("runner".getBytes());
        de.raindancer.modules.manhunt.model.Hunt hunt = de.raindancer.modules.manhunt.model.Hunt.of(
                java.util.Set.of(hunterId, runnerId), java.util.Set.of(runnerId));
        when(world.getName()).thenReturn("hunt");
        when(world.getEnvironment()).thenReturn(World.Environment.NORMAL);
        when(hunter.getUniqueId()).thenReturn(hunterId);
        when(hunter.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        ItemStack held = trackerStack(mock(org.bukkit.inventory.meta.CompassMeta.class));
        ItemStack[] contents = new ItemStack[36];
        contents[0] = held;
        when(inventory.getContents()).thenReturn(contents);
        when(inventory.getItem(0)).thenReturn(held);
        when(inventory.getItemInMainHand()).thenReturn(held);
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
    @DisplayName("refitted after a side change, somebody who no longer holds one has it taken")
    void fitTakesFromANonHolder() {
        java.util.UUID runnerId = java.util.UUID.nameUUIDFromBytes("runner".getBytes());
        java.util.UUID hunterId = java.util.UUID.nameUUIDFromBytes("hunter".getBytes());
        de.raindancer.modules.manhunt.model.Hunt hunt = de.raindancer.modules.manhunt.model.Hunt.of(
                java.util.Set.of(hunterId, runnerId), java.util.Set.of(runnerId));
        when(hunter.getUniqueId()).thenReturn(runnerId);
        ItemStack[] contents = new ItemStack[36];
        contents[2] = trackerStack(mock(org.bukkit.inventory.meta.CompassMeta.class));
        when(inventory.getContents()).thenReturn(contents);

        service.fit(hunt, hunter);

        verify(inventory).setItem(2, null);
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("picking from the list")
    class Picking {

        private final java.util.UUID runner = java.util.UUID.nameUUIDFromBytes("runner".getBytes());
        private final java.util.UUID hunterId = java.util.UUID.nameUUIDFromBytes("hunter".getBytes());
        private final java.util.UUID mate = java.util.UUID.nameUUIDFromBytes("mate".getBytes());
        private org.bukkit.Server server;

        private TrackerCompassService serviceWith(ManhuntSettings settings) {
            de.raindancer.modules.manhunt.model.Hunt hunt = de.raindancer.modules.manhunt.model.Hunt.of(
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
        private de.raindancer.modules.manhunt.model.Hunt hunt;

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
            hunt = de.raindancer.modules.manhunt.model.Hunt.of(
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
        @DisplayName("a caught Runner holds no compass any more")
        void caughtRunner() {
            TrackerCompassService service = serviceWith(ManhuntSettings.DEFAULTS.withRunnerCompass(true));
            hunt.eliminate(runner);

            assertThat(service.isHolder(hunt, runner)).isFalse();
        }
    }
}
