package de.raindancer.modules.manhunt.tracker;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.manhunt.ManhuntSettings;
import de.raindancer.modules.manhunt.model.Hunt;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("the Runners' structure compass")
class StructureCompassServiceTest {

    private static final UUID RUNNER = UUID.nameUUIDFromBytes("runner".getBytes());
    private static final UUID HUNTER = UUID.nameUUIDFromBytes("hunter".getBytes());

    private final World world = mock(World.class);
    private final Server server = mock(Server.class);
    private final Messages messages = mock(Messages.class);
    private final Hunt hunt = Hunt.of(Set.of(RUNNER, HUNTER), Set.of(RUNNER));
    private final AtomicReference<ManhuntSettings> settings =
            new AtomicReference<>(ManhuntSettings.DEFAULTS.withRunnerStructureCompass(true));
    private final List<List<String>> searchedFor = new ArrayList<>();
    private final AtomicReference<Location> found = new AtomicReference<>();
    private StructureCompassService service;
    private Player runner;
    private PlayerInventory inventory;
    private ItemStack compass;
    private CompassMeta meta;
    private String tag;

    @BeforeEach
    void setUp() {
        when(world.getName()).thenReturn("hunt");
        when(world.getEnvironment()).thenReturn(World.Environment.NORMAL);
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("manhunt");
        when(plugin.namespace()).thenReturn("manhunt");
        when(plugin.getServer()).thenReturn(server);
        runner = mock(Player.class);
        when(runner.getUniqueId()).thenReturn(RUNNER);
        when(runner.getWorld()).thenReturn(world);
        when(runner.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        when(runner.isOnline()).thenReturn(true);
        EntityScheduler scheduler = mock(EntityScheduler.class);
        when(scheduler.run(any(), any(), any())).thenAnswer(call -> {
            call.getArgument(1, Consumer.class).accept(null);
            return null;
        });
        when(runner.getScheduler()).thenReturn(scheduler);
        when(server.getPlayer(RUNNER)).thenReturn(runner);

        // The unchosen compass the runner was handed at the start, as a mock the service can recognise.
        tag = "unchosen";
        compass = mock(ItemStack.class);
        meta = mock(CompassMeta.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(compass.getType()).thenReturn(Material.COMPASS);
        when(compass.hasItemMeta()).thenReturn(true);
        when(compass.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(data);
        when(data.get(any(NamespacedKey.class), eq(PersistentDataType.STRING))).thenAnswer(call -> tag);
        org.mockito.Mockito.doAnswer(call -> tag = call.getArgument(2)).when(data)
                .set(any(NamespacedKey.class), eq(PersistentDataType.STRING), any(String.class));
        inventory = mock(PlayerInventory.class);
        ItemStack[] contents = new ItemStack[36];
        contents[3] = compass;
        when(inventory.getContents()).thenReturn(contents);
        when(inventory.getItem(3)).thenReturn(compass);
        when(runner.getInventory()).thenReturn(inventory);

        service = new StructureCompassService(plugin, () -> Optional.of(hunt), messages, settings::get,
                (origin, keys) -> {
                    searchedFor.add(keys);
                    return Optional.ofNullable(found.get());
                });
    }

    private StructureChoices.Choice village() {
        return StructureChoices.byId("village").orElseThrow();
    }

    @Test
    @DisplayName("choosing points it at the nearest one of that kind, once")
    void chooses() {
        found.set(new Location(world, 300, 70, -120));

        assertThat(service.choose(runner, village())).isTrue();

        assertThat(searchedFor).containsExactly(village().structureKeys());
        verify(meta).setLodestoneTracked(false);
        verify(meta).setLodestone(new Location(world, 300, 70, -120));
        verify(messages).send(eq(runner), eq("manhunt.structure.chosen"), any(Object[].class));
        assertThat(service.destinationOf(RUNNER)).isPresent();

        assertThat(service.choose(runner, village())).as("once per hunt").isFalse();
    }

    @Test
    @DisplayName("nothing of that kind nearby: said so, and they may choose again")
    void noneFound() {
        assertThat(service.choose(runner, village())).isFalse();

        verify(messages).send(eq(runner), eq("manhunt.structure.none"), any(Object[].class));
        found.set(new Location(world, 10, 64, 10));
        assertThat(service.choose(runner, StructureChoices.byId("igloo").orElseThrow())).isTrue();
    }

    @Test
    @DisplayName("within 20 blocks of it, the compass disappears")
    void reached() {
        found.set(new Location(world, 300, 70, -120));
        service.choose(runner, village());
        when(runner.getLocation()).thenReturn(new Location(world, 290, 20, -110));

        service.sweep(hunt);

        verify(inventory).setItem(3, null);
        verify(messages).send(eq(runner), eq("manhunt.structure.reached"), any(Object[].class));
        assertThat(service.destinationOf(RUNNER)).isEmpty();
    }

    @Test
    @DisplayName("thrown out, it is gone — the item vanishes instead of landing")
    void dropped() {
        found.set(new Location(world, 300, 70, -120));
        service.choose(runner, village());
        Item dropped = mock(Item.class);
        when(dropped.getItemStack()).thenReturn(compass);
        PlayerDropItemEvent event = new PlayerDropItemEvent(runner, dropped);

        service.onDrop(event);

        verify(dropped).remove();
        assertThat(event.isCancelled()).isFalse();
        assertThat(service.destinationOf(RUNNER)).isEmpty();
    }

    @Test
    @DisplayName("only a Runner may choose, and not with the setting off")
    void refusals() {
        Player hunter = mock(Player.class);
        when(hunter.getUniqueId()).thenReturn(HUNTER);
        found.set(new Location(world, 1, 1, 1));
        assertThat(service.choose(hunter, village())).isFalse();

        settings.set(ManhuntSettings.DEFAULTS.withRunnerStructureCompass(false));
        assertThat(service.choose(runner, village())).isFalse();
        assertThat(searchedFor).isEmpty();
    }

    @Test
    @DisplayName("switched off, nobody is handed one")
    void offGivesNothing() {
        settings.set(ManhuntSettings.DEFAULTS.withRunnerStructureCompass(false));

        service.armFor(hunt);

        verify(inventory, never()).addItem(any(ItemStack.class));
    }
}
