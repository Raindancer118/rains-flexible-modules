package de.raindancer.modules.warp;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.network.Arrivals;
import de.raindancer.core.platform.network.LocalBus;
import de.raindancer.core.platform.network.ThisServer;
import de.raindancer.core.testkit.TestDatabases;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.poi.PoiStore;
import de.raindancer.core.world.teleport.Travel;
import de.raindancer.core.world.teleport.TravelWatcher;
import de.raindancer.core.world.teleport.Trip;
import de.raindancer.modules.warp.model.Warp;
import de.raindancer.modules.warp.rules.WarpAccessRule;
import de.raindancer.modules.warp.service.TravelService;
import de.raindancer.modules.warp.store.WarpCatalogue;
import de.raindancer.modules.warp.store.WarpRegistry;
import de.raindancer.modules.warp.util.PermissionNodes;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("database")
class WarpsOnANetworkTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @TempDir
    Path folder;

    private final LocalBus.Hub hub = new LocalBus.Hub();
    private final List<Database> databases = new ArrayList<>();
    private final World world = mock(World.class);

    private WarpCatalogue server(String id) {
        Database database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE);
        databases.add(database);
        PoiStore store = new PoiStore(database);
        store.load();
        store.connect(hub.bus(id));
        return new WarpCatalogue(new WarpRegistry(store, () -> 0L, name -> true), store::flush);
    }

    @AfterEach
    void close() {
        databases.forEach(Database::close);
        ThisServer.set("");
    }

    private Location at() {
        when(world.getName()).thenReturn("world");
        return new Location(world, 5, 64, 7, 90, 0);
    }

    @Test
    @DisplayName("a warp made on one server is listed on the other with the server it is on")
    void warpsAreTheNetworks() {
        WarpCatalogue smp = server("smp");
        WarpCatalogue arena = server("arena");
        Location here = at();
        ThisServer.set("smp");
        smp.create("spawn", here, ALEX);
        hub.drain();

        Warp seen = arena.byName("spawn").orElseThrow();
        assertThat(seen.server()).isEqualTo("smp");
        assertThat(arena.count()).isEqualTo(1);
        ThisServer.set("arena");
        assertThat(seen.isHere()).isFalse();
        assertThat(seen.isReachable()).isTrue();
    }

    @Test
    @DisplayName("a name is one warp for the whole network: making it again elsewhere replaces it, and a deletion is felt everywhere")
    void oneNameOneWarp() {
        WarpCatalogue smp = server("smp");
        WarpCatalogue arena = server("arena");
        Location here = at();
        ThisServer.set("smp");
        smp.create("spawn", here, ALEX);
        hub.drain();

        ThisServer.set("arena");
        arena.create("spawn", here, ALEX);
        hub.drain();
        assertThat(smp.count()).isEqualTo(1);
        assertThat(smp.byName("spawn").orElseThrow().server()).isEqualTo("arena");
        arena.delete("spawn");
        hub.drain();
        assertThat(smp.byName("spawn")).isEmpty();
    }

    @Test
    @DisplayName("using a warp on another server waits, then sends the player there at its place")
    void goingAcross() {
        WarpCatalogue smp = server("smp");
        WarpCatalogue arena = server("arena");
        Location here = at();
        ThisServer.set("smp");
        smp.create("spawn", here, ALEX);
        hub.drain();
        ThisServer.set("arena");

        Travel travel = mock(Travel.class);
        TravelService service = new TravelService(arena,
                new WarpRegistry(mock(PoiStore.class), () -> 0L, name -> true), travel, new WarpAccessRule(),
                mock(Messages.class), WarpSettings.DEFAULTS.withWarmupSeconds(0));
        List<String> sentTo = new ArrayList<>();
        List<Arrivals.Destination> landed = new ArrayList<>();
        service.acrossServers((player, target, destination) -> {
            sentTo.add(target);
            landed.add(destination);
        });
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(ALEX);
        when(player.hasPermission(anyString())).thenAnswer(ask -> PermissionNodes.USE.equals(ask.getArgument(0)));
        when(player.getLocation()).thenReturn(here);

        service.goTo(player, "spawn");
        ArgumentCaptor<TravelWatcher> watcher = ArgumentCaptor.forClass(TravelWatcher.class);
        verify(travel).go(eq(player), any(Location.class), any(Trip.class), watcher.capture());
        assertThat(sentTo).isEmpty();
        watcher.getValue().arrived(player, here, Trip.to("spawn"));

        assertThat(sentTo).containsExactly("smp");
        assertThat(landed).singleElement().satisfies(where -> {
            assertThat(where.world()).isEqualTo("world");
            assertThat(where.z()).isEqualTo(7);
        });
    }
}
