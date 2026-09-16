package de.raindancer.modules.worldutils.service;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.manage.SeedHistory;
import de.raindancer.core.world.manage.WorldRegenerator;
import de.raindancer.core.world.manage.WorldSeed;
import de.raindancer.modules.worldutils.model.Dimension;
import de.raindancer.modules.worldutils.model.ManagedWorld;
import de.raindancer.modules.worldutils.store.LastPositions;
import de.raindancer.modules.worldutils.store.ManagedWorldStore;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorldAdminServiceTest {

    @TempDir
    Path folder;

    private final Plugin plugin = mock(Plugin.class);
    private final Server server = mock(Server.class);
    private final WorldRegenerator regenerator = mock(WorldRegenerator.class);
    private final SeedHistory history = mock(SeedHistory.class);
    private final LastPositions positions = mock(LastPositions.class);
    private final Messages messages = mock(Messages.class);
    private final CommandSender sender = mock(CommandSender.class);
    private ManagedWorldStore managed;
    private MockedStatic<Bukkit> bukkit;
    private WorldAdminService service;

    @BeforeEach
    void setUp() {
        managed = new ManagedWorldStore(folder);
        managed.load();
        bukkit = mockStatic(Bukkit.class);
        GlobalRegionScheduler scheduler = mock(GlobalRegionScheduler.class);
        bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(scheduler);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).when(scheduler).execute(eq(plugin), any(Runnable.class));
        bukkit.when(Bukkit::getWorlds).thenReturn(List.of(mock(World.class)));
        service = new WorldAdminService(plugin, server, regenerator, history, managed, positions, messages,
                Log.of("worldutils-test"));
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
    }

    private World loaded(String name) {
        World world = mock(World.class);
        when(world.getName()).thenReturn(name);
        when(world.getKey()).thenReturn(NamespacedKey.minecraft(name));
        when(server.getWorld(name)).thenReturn(world);
        return world;
    }

    @Test
    @DisplayName("a family is three worlds made from one seed, and all three are remembered for the next boot")
    void createAFamily() {
        when(regenerator.create(anyString(), any(), any())).thenReturn(true);

        service.create(sender, "farm", null, true, WorldSeed.random());

        ArgumentCaptor<WorldSeed> seeds = ArgumentCaptor.forClass(WorldSeed.class);
        verify(regenerator).create(eq("farm"), eq(World.Environment.NORMAL), seeds.capture());
        verify(regenerator).create(eq("farm_nether"), eq(World.Environment.NETHER), seeds.capture());
        verify(regenerator).create(eq("farm_the_end"), eq(World.Environment.THE_END), seeds.capture());
        assertThat(seeds.getAllValues()).as("one seed, so the dimensions belong together")
                .allMatch(seed -> seed.kind() == WorldSeed.Kind.FIXED)
                .containsOnly(seeds.getAllValues().getFirst());
        assertThat(new ManagedWorldStore(folder).load()).hasSize(3);
    }

    @Test
    @DisplayName("a chosen seed is passed through, and a _nether name makes a nether without being told")
    void createOneWithASeed() {
        when(regenerator.create(anyString(), any(), any())).thenReturn(true);

        service.create(sender, "mine_nether", null, false, WorldSeed.fixed(42));
        service.create(sender, "flat", Dimension.END, false, WorldSeed.fixed(7));

        verify(regenerator).create("mine_nether", World.Environment.NETHER, WorldSeed.fixed(42));
        verify(regenerator).create("flat", World.Environment.THE_END, WorldSeed.fixed(7));
    }

    @Test
    @DisplayName("an existing world or an impossible name is refused before anything is made")
    void refusals() {
        loaded("farm");

        service.create(sender, "farm", null, false, null);
        service.create(sender, "Bad Name", null, false, null);

        verify(regenerator, never()).create(anyString(), any(), any());
        verify(messages).send(sender, "worldutils.already-exists", "world", "farm");
        verify(messages).send(eq(sender), eq("worldutils.invalid-name"), any(), any());
    }

    @Test
    @DisplayName("regenerating a family hands Core every loaded member as one group, then forgets positions in them")
    void regenerateAFamily() {
        World farm = loaded("farm");
        World nether = loaded("farm_nether");
        doAnswer(invocation -> {
            invocation.<Consumer<Boolean>>getArgument(2).accept(true);
            return null;
        }).when(regenerator).regenerateAll(any(), any(), any());

        service.regenerate(sender, farm, WorldSeed.same(), true);

        verify(regenerator).regenerateAll(eq(List.of(farm, nether)), eq(WorldSeed.same()), any());
        verify(positions).forgetWorld("farm");
        verify(positions).forgetWorld("farm_nether");
    }

    @Test
    @DisplayName("the server's own worlds are refused with a reason on the sender's screen, not only in the log")
    void serverWorldsAreRefused() {
        World vanillaNether = loaded("world_nether");
        when(vanillaNether.getKey()).thenReturn(NamespacedKey.minecraft("the_nether"));

        service.regenerate(sender, vanillaNether, WorldSeed.random(), false);
        service.delete(sender, vanillaNether, false);

        verify(regenerator, never()).regenerateAll(any(), any(), any());
        verify(regenerator, never()).deleteAll(any(), any());
        verify(messages, org.mockito.Mockito.times(2)).send(sender, "worldutils.server-world", "world", "world_nether");
    }

    @Test
    @DisplayName("a deleted world is no longer loaded at the next boot")
    void deleteForgets() {
        managed.add(new ManagedWorld("farm", World.Environment.NORMAL));
        World farm = loaded("farm");
        doAnswer(invocation -> {
            when(server.getWorld("farm")).thenReturn(null);
            invocation.<Consumer<Boolean>>getArgument(1).accept(true);
            return null;
        }).when(regenerator).deleteAll(any(), any());

        service.delete(sender, farm, false);

        assertThat(new ManagedWorldStore(folder).load()).isEmpty();
        verify(positions).forgetWorld("farm");
        verify(messages).send(sender, "worldutils.deleted", "worlds", "farm");
    }

    @Test
    @DisplayName("after a restart, every world made here that is not loaded is loaded — without a history entry")
    void loadsWhatItMade() {
        managed.add(new ManagedWorld("farm", World.Environment.NORMAL));
        managed.add(new ManagedWorld("farm_nether", World.Environment.NETHER));
        loaded("farm");
        when(regenerator.load("farm_nether", World.Environment.NETHER)).thenReturn(true);

        assertThat(service.loadManaged()).isEqualTo(1);
        verify(regenerator, never()).load(eq("farm"), any());
        verify(regenerator, never()).create(anyString(), any(), any());
    }
}
