package de.raindancer.modules.worldutils.listener;

import de.raindancer.modules.worldutils.WorldUtilsSettings;
import de.raindancer.modules.worldutils.model.ManagedWorld;
import de.raindancer.modules.worldutils.store.ManagedWorldStore;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PortalLinkListenerTest {

    @TempDir
    Path folder;

    private ManagedWorldStore managed;
    private World farm;
    private World farmNether;
    private World farmEnd;
    private World serverOverworld;
    private World serverNether;

    private static World world(String name, World.Environment environment) {
        World world = mock(World.class);
        when(world.getName()).thenReturn(name);
        when(world.getEnvironment()).thenReturn(environment);
        when(world.getKey()).thenReturn(NamespacedKey.minecraft(name));
        return world;
    }

    private static Location at(World world) {
        Location location = mock(Location.class);
        when(location.isWorldLoaded()).thenReturn(true);
        when(location.getWorld()).thenReturn(world);
        Location copy = mock(Location.class);
        when(location.clone()).thenReturn(copy);
        return location;
    }

    private PortalLinkListener listener(WorldUtilsSettings settings) {
        Map<String, World> worlds = Map.of("farm", farm, "farm_nether", farmNether, "farm_the_end", farmEnd);
        return new PortalLinkListener(managed, () -> settings, worlds::get);
    }

    @BeforeEach
    void setUp() {
        managed = new ManagedWorldStore(folder);
        managed.load();
        farm = world("farm", World.Environment.NORMAL);
        farmNether = world("farm_nether", World.Environment.NETHER);
        farmEnd = world("farm_the_end", World.Environment.THE_END);
        serverOverworld = world("world", World.Environment.NORMAL);
        when(serverOverworld.getKey()).thenReturn(NamespacedKey.minecraft("overworld"));
        serverNether = world("world_nether", World.Environment.NETHER);
        when(serverNether.getKey()).thenReturn(NamespacedKey.minecraft("the_nether"));
    }

    @Test
    @DisplayName("a portal in a world made here, sent to the server's nether, is sent to its own")
    void redirected() {
        managed.add(new ManagedWorld("farm", World.Environment.NORMAL));

        Optional<Location> corrected = listener(WorldUtilsSettings.DEFAULTS)
                .corrected(at(farm), at(serverNether), null);

        assertThat(corrected).isPresent();
    }

    @Test
    @DisplayName("worlds not made here, the server's own level, and the setting switched off are left alone")
    void leftAlone() {
        assertThat(listener(WorldUtilsSettings.DEFAULTS).corrected(at(farm), at(serverNether), null))
                .as("farm was not made here").isEmpty();

        managed.add(new ManagedWorld("farm", World.Environment.NORMAL));
        assertThat(listener(WorldUtilsSettings.DEFAULTS.withLinkPortals(false))
                .corrected(at(farm), at(serverNether), null)).isEmpty();
        assertThat(listener(WorldUtilsSettings.DEFAULTS).corrected(at(serverOverworld), at(serverNether), null))
                .as("the server's own portals already work").isEmpty();
    }

    @Test
    @DisplayName("the End's exit portal lands at the family overworld's spawn, not the server's coordinates")
    void endExit() {
        managed.add(new ManagedWorld("farm", World.Environment.NORMAL));
        Location farmSpawn = mock(Location.class);
        when(farm.getSpawnLocation()).thenReturn(farmSpawn);
        Player player = mock(Player.class);

        Optional<Location> corrected = listener(WorldUtilsSettings.DEFAULTS)
                .corrected(at(farmEnd), at(serverOverworld), player);

        assertThat(corrected).containsSame(farmSpawn);
    }
}
