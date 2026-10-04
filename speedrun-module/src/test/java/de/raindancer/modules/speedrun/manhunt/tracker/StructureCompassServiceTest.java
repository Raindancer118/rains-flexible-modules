package de.raindancer.modules.speedrun.manhunt.tracker;

import de.raindancer.core.testkit.TestInventories;
import de.raindancer.core.testkit.TestPlayers;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.speedrun.manhunt.ManhuntSettings;
import de.raindancer.modules.speedrun.manhunt.model.Hunt;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Runners' structure compass, with real items from Core's testkit and the search behind Core's
 * {@code StructureLocator} — answered here by a future the test completes when it likes, which is
 * exactly what a search spread over many ticks looks like from the outside.
 */
@DisplayName("the Runners' structure compass")
class StructureCompassServiceTest {

    private final World world = mock(World.class);
    private final Server server = mock(Server.class);
    private final Messages messages = mock(Messages.class);
    private final AtomicReference<ManhuntSettings> settings =
            new AtomicReference<>(ManhuntSettings.DEFAULTS.withRunnerStructureCompass(true));
    private final List<List<String>> searchedFor = new ArrayList<>();
    private final List<CompletableFuture<Optional<Location>>> searches = new ArrayList<>();
    private final java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1_000_000);
    private StructureCompassService service;
    private Player runner;
    private Player hunter;
    private UUID runnerId;
    private Hunt hunt;
    private PlayerInventory inventory;

    @BeforeEach
    void setUp() {
        when(world.getName()).thenReturn("hunt");
        when(world.getEnvironment()).thenReturn(World.Environment.NORMAL);
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("manhunt");
        when(plugin.namespace()).thenReturn("manhunt");
        when(plugin.getServer()).thenReturn(server);
        runner = TestPlayers.player("Runner");
        runnerId = runner.getUniqueId();
        hunter = TestPlayers.player("Hunter");
        when(runner.getWorld()).thenReturn(world);
        when(runner.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        when(runner.isOnline()).thenReturn(true);
        when(server.getPlayer(runnerId)).thenReturn(runner);
        inventory = runner.getInventory();
        hunt = Hunt.of(Set.of(runnerId, hunter.getUniqueId()), Set.of(runnerId));

        service = new StructureCompassService(plugin, () -> Optional.of(hunt), messages, settings::get,
                (origin, keys) -> {
                    searchedFor.add(keys);
                    CompletableFuture<Optional<Location>> search = new CompletableFuture<>();
                    searches.add(search);
                    return search;
                }, now::get);
        // The blank compass every Runner is handed at the start.
        service.give(runner);
    }

    private StructureChoices.Choice village() {
        return StructureChoices.byId("village").orElseThrow();
    }

    private ItemStack theCompass() {
        return TestInventories.stacksIn(inventory).stream().filter(service::isStructureCompass)
                .findFirst().orElse(null);
    }

    private void answer(Location found) {
        searches.getLast().complete(Optional.ofNullable(found));
    }

    @Test
    @DisplayName("the blank compass is handed over once, tagged as ours")
    void handedOver() {
        service.give(runner);

        assertThat(TestInventories.stacksIn(inventory)).singleElement()
                .satisfies(stack -> assertThat(service.isBlank(stack)).isTrue());
        verify(messages).send(eq(runner), eq("manhunt.structure.given"), any(Object[].class));
    }

    @Test
    @DisplayName("choosing searches without holding the server, then points it at the nearest one — once")
    void chooses() {
        CompletableFuture<Boolean> chosen = service.choose(runner, village());

        // Searching, over several ticks: nothing is decided yet, and they are told it is looking.
        assertThat(chosen).isNotDone();
        assertThat(searchedFor).containsExactly(village().structureKeys());
        verify(messages).send(eq(runner), eq("manhunt.structure.searching"), any(Object[].class));

        answer(new Location(world, 300, 70, -120));

        assertThat(chosen).isCompletedWithValue(true);
        CompassMeta meta = (CompassMeta) theCompass().getItemMeta();
        assertThat(meta.isLodestoneTracked()).isFalse();
        assertThat(meta.getLodestone()).isEqualTo(new Location(world, 300, 70, -120));
        assertThat(service.isBlank(theCompass())).isFalse();
        verify(messages).send(eq(runner), eq("manhunt.structure.chosen"), any(Object[].class));
        assertThat(service.destinationOf(runnerId)).isPresent();

        assertThat(service.choose(runner, village())).as("once per hunt").isCompletedWithValue(false);
    }

    @Test
    @DisplayName("a second choice while the first is still searching is refused, not searched twice")
    void oneSearchAtATime() {
        service.choose(runner, village());
        now.addAndGet(StructureCompassService.SEARCH_COOLDOWN_MILLIS * 3);

        assertThat(service.choose(runner, StructureChoices.byId("igloo").orElseThrow()))
                .isCompletedWithValue(false);

        assertThat(searchedFor).hasSize(1);
        verify(messages).send(eq(runner), eq("manhunt.structure.still-searching"), any(Object[].class));
    }

    @Test
    @DisplayName("nothing of that kind nearby: said so, and they may choose again")
    void noneFound() {
        CompletableFuture<Boolean> first = service.choose(runner, village());
        answer(null);

        assertThat(first).isCompletedWithValue(false);
        verify(messages).send(eq(runner), eq("manhunt.structure.none"), any(Object[].class));
        now.addAndGet(StructureCompassService.SEARCH_COOLDOWN_MILLIS);
        CompletableFuture<Boolean> second = service.choose(runner, StructureChoices.byId("igloo").orElseThrow());
        answer(new Location(world, 10, 64, 10));
        assertThat(second).isCompletedWithValue(true);
    }

    @Test
    @DisplayName("a search that failed outright is said as nothing found, never left hanging")
    void searchFailed() {
        CompletableFuture<Boolean> chosen = service.choose(runner, village());
        searches.getLast().completeExceptionally(new IllegalStateException("world unloaded"));

        assertThat(chosen).isCompletedWithValue(false);
        verify(messages).send(eq(runner), eq("manhunt.structure.none"), any(Object[].class));
    }

    @Test
    @DisplayName("thrown away while it searched: the answer finds no compass and points nothing")
    void droppedWhileSearching() {
        CompletableFuture<Boolean> chosen = service.choose(runner, village());
        inventory.clear();

        answer(new Location(world, 300, 70, -120));

        assertThat(chosen).isCompletedWithValue(false);
        assertThat(service.destinationOf(runnerId)).isEmpty();
    }

    @Test
    @DisplayName("within 20 blocks of it, the compass disappears")
    void reached() {
        service.choose(runner, village());
        answer(new Location(world, 300, 70, -120));
        when(runner.getLocation()).thenReturn(new Location(world, 290, 20, -110));

        service.sweep(hunt);

        assertThat(theCompass()).isNull();
        verify(messages).send(eq(runner), eq("manhunt.structure.reached"), any(Object[].class));
        assertThat(service.destinationOf(runnerId)).isEmpty();
    }

    @Test
    @DisplayName("thrown out, it is gone — the item vanishes instead of landing")
    void dropped() {
        service.choose(runner, village());
        answer(new Location(world, 300, 70, -120));
        Item dropped = mock(Item.class);
        ItemStack compass = theCompass();
        when(dropped.getItemStack()).thenReturn(compass);
        PlayerDropItemEvent event = new PlayerDropItemEvent(runner, dropped);

        service.onDrop(event);

        verify(dropped).remove();
        assertThat(event.isCancelled()).isFalse();
        assertThat(service.destinationOf(runnerId)).isEmpty();
    }

    @Test
    @DisplayName("only a Runner may choose, and not with the setting off")
    void refusals() {
        assertThat(service.choose(hunter, village())).isCompletedWithValue(false);

        settings.set(ManhuntSettings.DEFAULTS.withRunnerStructureCompass(false));
        assertThat(service.choose(runner, village())).isCompletedWithValue(false);
        assertThat(searchedFor).isEmpty();
    }

    @Test
    @DisplayName("a Runner turning Hunter has it taken back, and where it pointed forgotten")
    void sideChangeTakesItBack() {
        service.choose(runner, village());
        answer(new Location(world, 300, 70, -120));
        Hunt bigger = Hunt.of(Set.of(runnerId, hunter.getUniqueId(), UUID.randomUUID()),
                Set.of(runnerId, hunter.getUniqueId()));
        bigger.moveToHunters(runnerId);

        service.fit(bigger, runner);

        assertThat(theCompass()).isNull();
        assertThat(service.destinationOf(runnerId)).isEmpty();
    }

    @Test
    @DisplayName("switched off, nobody is handed one")
    void offGivesNothing() {
        inventory.clear();
        settings.set(ManhuntSettings.DEFAULTS.withRunnerStructureCompass(false));

        service.fit(hunt, runner);

        assertThat(TestInventories.stacksIn(inventory)).isEmpty();
    }

    @Test
    @DisplayName("a search that found nothing cannot be repeated at once")
    void searchCooldown() {
        service.choose(runner, village());
        answer(null);

        assertThat(service.choose(runner, village())).isCompletedWithValue(false);

        assertThat(searchedFor).hasSize(1);
        verify(messages).send(eq(runner), eq("manhunt.structure.wait"), any(Object[].class));
        verify(messages, never()).send(eq(runner), eq("manhunt.structure.still-searching"), any(Object[].class));
    }
}
