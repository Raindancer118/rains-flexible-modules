package de.raindancer.modules.speedrun.manhunt.tracker;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.speedrun.manhunt.ManhuntSettings;
import de.raindancer.modules.speedrun.manhunt.model.Hunt;
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
    private final java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1_000_000);
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
        when(compass.getPersistentDataContainer()).thenReturn(data);
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
                }, now::get);
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
        now.addAndGet(StructureCompassService.SEARCH_COOLDOWN_MILLIS);
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
    @DisplayName("a Runner turning Hunter has it taken back, and where it pointed forgotten")
    void sideChangeTakesItBack() {
        found.set(new Location(world, 300, 70, -120));
        service.choose(runner, village());
        Hunt bigger = Hunt.of(Set.of(RUNNER, HUNTER, UUID.randomUUID()), Set.of(RUNNER, HUNTER));
        bigger.moveToHunters(RUNNER);

        service.fit(bigger, runner);

        verify(inventory).setItem(3, null);
        assertThat(service.destinationOf(RUNNER)).isEmpty();
    }

    @Test
    @DisplayName("switched off, nobody is handed one")
    void offGivesNothing() {
        settings.set(ManhuntSettings.DEFAULTS.withRunnerStructureCompass(false));

        service.fit(hunt, runner);

        verify(inventory, never()).addItem(any(ItemStack.class));
    }

    @Test
    @DisplayName("a search that found nothing cannot be repeated at once — every search can hold the server for seconds")
    void searchCooldown() {
        assertThat(service.choose(runner, village())).isFalse();

        assertThat(service.choose(runner, village())).isFalse();

        assertThat(searchedFor).hasSize(1);
        verify(messages).send(eq(runner), eq("manhunt.structure.wait"), any(Object[].class));
    }

    @Test
    @DisplayName("one search per choice: variants of one kind are searched together, never one after another")
    void onePassPerChoice() {
        java.util.Map<String, String> types = java.util.Map.of(
                "shipwreck", "shipwreck", "shipwreck_beached", "shipwreck",
                "village_plains", "jigsaw", "village_desert", "jigsaw",
                "bastion_remnant", "jigsaw", "fortress", "fortress");

        assertThat(StructureCompassService.plan(List.of("fortress"), types::get))
                .isEqualTo(StructureCompassService.SearchPlan.ONE);
        assertThat(StructureCompassService.plan(List.of("shipwreck", "shipwreck_beached"), types::get))
                .isEqualTo(StructureCompassService.SearchPlan.BY_TYPE);
        assertThat(StructureCompassService.plan(List.of("village_plains", "village_desert"), types::get))
                .as("villages share the jigsaw type with half the game — searched as villages")
                .isEqualTo(StructureCompassService.SearchPlan.VILLAGES);
        assertThat(StructureCompassService.plan(List.of("fortress", "bastion_remnant"), types::get))
                .isEqualTo(StructureCompassService.SearchPlan.EACH);
    }

    @Test
    @DisplayName("every multi-variant choice the compass offers is one search")
    void everyOfferedChoiceIsOnePass() {
        java.util.Map<String, String> types = new java.util.HashMap<>();
        for (StructureChoices.Choice choice : StructureChoices.all()) {
            for (String key : choice.structureKeys()) {
                // Vanilla's own types for these ids.
                types.put(key, key.startsWith("village_") ? "jigsaw"
                        : key.startsWith("ruined_portal") ? "ruined_portal"
                        : key.startsWith("shipwreck") ? "shipwreck"
                        : key.startsWith("ocean_ruin") ? "ocean_ruin"
                        : key.startsWith("mineshaft") ? "mineshaft" : key);
            }
        }
        for (StructureChoices.Choice choice : StructureChoices.all()) {
            assertThat(StructureCompassService.plan(choice.structureKeys(), types::get))
                    .as(choice.id()).isNotEqualTo(StructureCompassService.SearchPlan.EACH);
        }
    }
}
