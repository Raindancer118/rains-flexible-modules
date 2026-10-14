package de.raindancer.modules.homes;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.network.Arrivals;
import de.raindancer.core.platform.network.LocalBus;
import de.raindancer.core.platform.network.ThisServer;
import de.raindancer.core.testkit.TestDatabases;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.poi.Poi;
import de.raindancer.core.world.poi.PoiStore;
import de.raindancer.core.world.teleport.Travel;
import de.raindancer.core.world.teleport.TravelWatcher;
import de.raindancer.core.world.teleport.Trip;
import de.raindancer.modules.homes.model.Home;
import de.raindancer.modules.homes.service.HomeTravelService;
import de.raindancer.modules.homes.store.HomeCatalogue;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("database")
class HomesOnANetworkTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @TempDir
    Path folder;

    private final LocalBus.Hub hub = new LocalBus.Hub();
    private final List<Database> databases = new ArrayList<>();

    private HomeCatalogue server(String id) {
        Database database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE);
        databases.add(database);
        PoiStore store = new PoiStore(database);
        store.load();
        store.connect(hub.bus(id));
        return new HomeCatalogue(store, store::flush);
    }

    @AfterEach
    void close() {
        databases.forEach(Database::close);
        ThisServer.set("");
    }

    private static Location at(World world) {
        return new Location(world, 5, 64, 7, 90, 0);
    }

    private final World world = world();

    private static World world() {
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        return world;
    }

    @Test
    @DisplayName("a home set on one server is on the other, stamped with the server it was set on")
    void followsThePlayer() {
        HomeCatalogue smp = server("smp");
        HomeCatalogue arena = server("arena");
        ThisServer.set("smp");
        smp.set(ALEX, "Alex", "base", at(world));
        hub.drain();

        Home seen = arena.find(ALEX, "base").orElseThrow();
        assertThat(seen.server()).isEqualTo("smp");
        ThisServer.set("arena");
        assertThat(seen.isHere()).isFalse();
        assertThat(seen.isReachable()).as("a home elsewhere is reached by going there").isTrue();
        assertThat(arena.count(ALEX)).isEqualTo(1);
    }

    @Test
    @DisplayName("homes on every server count towards one limit, and one name is one home")
    void limitsCountAcrossTheNetwork() {
        HomeCatalogue smp = server("smp");
        HomeCatalogue arena = server("arena");
        ThisServer.set("smp");
        smp.set(ALEX, "Alex", "base", at(world));
        ThisServer.set("arena");
        arena.set(ALEX, "Alex", "farm", at(world));
        hub.drain();

        assertThat(smp.count(ALEX)).isEqualTo(2);
        assertThat(arena.count(ALEX)).isEqualTo(2);
        assertThat(smp.has(ALEX, "farm")).isTrue();
    }

    @Test
    @DisplayName("setting a home again on another server moves it there, and keeps its id")
    void movingAHomeRestampsIt() {
        HomeCatalogue smp = server("smp");
        HomeCatalogue arena = server("arena");
        ThisServer.set("smp");
        Home first = smp.set(ALEX, "Alex", "base", at(world)).orElseThrow();
        hub.drain();
        ThisServer.set("arena");
        Home moved = arena.set(ALEX, "Alex", "base", at(world)).orElseThrow();
        hub.drain();

        assertThat(moved.poi().id()).isEqualTo(first.poi().id());
        assertThat(smp.find(ALEX, "base").orElseThrow().server()).isEqualTo("arena");
    }

    @Test
    @DisplayName("renaming a home from another server keeps it on the server it belongs to")
    void renamingKeepsTheServer() {
        HomeCatalogue smp = server("smp");
        HomeCatalogue arena = server("arena");
        ThisServer.set("smp");
        smp.set(ALEX, "Alex", "base", at(world));
        hub.drain();
        ThisServer.set("arena");
        arena.rename(ALEX, "base", "castle");
        hub.drain();

        assertThat(smp.find(ALEX, "castle").orElseThrow().server()).isEqualTo("smp");
    }

    @Test
    @DisplayName("going to a home on another server sends the player there, at the place")
    void goingAcross() {
        HomeCatalogue smp = server("smp");
        server("arena");
        ThisServer.set("smp");
        Home home = smp.set(ALEX, "Alex", "base", at(world)).orElseThrow();
        ThisServer.set("arena");

        Travel travel = mock(Travel.class);
        HomeTravelService service = new HomeTravelService(travel, mock(Messages.class), null,
                HomeSettings.DEFAULTS.withWarmupSeconds(0));
        List<String> sentTo = new ArrayList<>();
        List<Arrivals.Destination> landed = new ArrayList<>();
        service.acrossServers((player, target, destination) -> {
            sentTo.add(target);
            landed.add(destination);
        });
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(ALEX);
        when(player.getLocation()).thenReturn(at(world));

        service.go(player, home);
        ArgumentCaptor<TravelWatcher> watcher = ArgumentCaptor.forClass(TravelWatcher.class);
        verify(travel).go(eq(player), any(Location.class), any(Trip.class), watcher.capture());
        assertThat(sentTo).as("nothing is sent before the wait is over").isEmpty();
        watcher.getValue().arrived(player, at(world), Trip.to("base"));

        assertThat(sentTo).containsExactly("smp");
        assertThat(landed).singleElement().satisfies(where -> {
            assertThat(where.world()).isEqualTo("world");
            assertThat(where.x()).isEqualTo(5);
            assertThat(where.yaw()).isEqualTo(90f);
        });
    }

    @Test
    @DisplayName("without a way to send players, a home on another server is refused, not walked to")
    void noWayToSend() {
        HomeCatalogue smp = server("smp");
        ThisServer.set("smp");
        Home home = smp.set(ALEX, "Alex", "base", at(world)).orElseThrow();
        ThisServer.set("arena");
        Travel travel = mock(Travel.class);
        HomeTravelService service = new HomeTravelService(travel, mock(Messages.class), null,
                HomeSettings.DEFAULTS);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(ALEX);

        service.go(player, home);

        verify(travel, never()).go(any(), any(), any(), any());
    }
}
