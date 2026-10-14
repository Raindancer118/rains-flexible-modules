package de.raindancer.modules.playerutils.service;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.moderation.vanish.Vanish;
import de.raindancer.core.moderation.vanish.VanishSink;
import de.raindancer.core.platform.network.Arrivals;
import de.raindancer.core.platform.network.LocalBus;
import de.raindancer.core.platform.network.NetworkCalls;
import de.raindancer.core.platform.network.NetworkPlayers;
import de.raindancer.core.platform.network.NetworkServers;
import de.raindancer.core.platform.network.NetworkTravel;
import de.raindancer.core.platform.network.Presence;
import de.raindancer.core.testkit.TestDatabases;
import org.bukkit.GameMode;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Finding, locating and sending players across two servers: Core's real presence and calls over one bus. */
@Tag("database")
class NetworkReachTest {

    @TempDir
    Path folder;

    private final LocalBus.Hub hub = new LocalBus.Hub();
    private final List<Database> databases = new ArrayList<>();
    private final List<AutoCloseable> open = new ArrayList<>();
    private final List<Side> sides = new ArrayList<>();
    private final AtomicLong clock = new AtomicLong(1_000_000);

    private final class Side {
        final String id;
        final Server server = mock(Server.class);
        final Vanish vanish = new Vanish(mock(VanishSink.class));
        final NetworkTravel travel = mock(NetworkTravel.class);
        final Presence presence;
        final NetworkServers servers;
        final NetworkReach reach;

        Side(String id) {
            this.id = id;
            Database database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE);
            databases.add(database);
            servers = new NetworkServers(database, id, "default", System::currentTimeMillis);
            presence = new Presence(database, servers, System::currentTimeMillis);
            LocalBus bus = hub.bus(id);
            presence.connect(bus);
            NetworkPlayers players = new NetworkPlayers(presence, servers, bus, (player, key, values) -> false);
            NetworkCalls calls = new NetworkCalls(bus);
            open.add(calls);
            servers.heartbeat(id, 0);
            reach = new NetworkReach(server, vanish, player -> CompletableFuture.completedFuture(Optional.of(
                    new NetworkReach.Spot("world-" + id, 1.5, 64, -3.5, 90f, 10f))), clock::get);
            when(travel.travel(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(true));
            reach.connect(players, calls, travel);
            reach.timeout(Duration.ofMillis(500));
            sides.add(this);
        }

        Player join(String name) {
            Player player = mock(Player.class);
            UUID uuid = UUID.nameUUIDFromBytes(name.getBytes());
            when(player.getName()).thenReturn(name);
            when(player.getUniqueId()).thenReturn(uuid);
            when(player.isOnline()).thenReturn(true);
            when(server.getPlayerExact(name)).thenReturn(player);
            when(server.getPlayer(uuid)).thenReturn(player);
            presence.joined(uuid, name, false);
            return player;
        }
    }

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable one : open) {
            one.close();
        }
        databases.forEach(Database::close);
    }

    private void settle() {
        hub.drain();
        sides.forEach(side -> {
            side.servers.refresh();
            side.presence.refreshAll();
        });
    }

    @Test
    @DisplayName("a player on another server is found by name; one on this server is not 'elsewhere'; a vanished one only for staff who may see them")
    void finds() {
        Side smp = new Side("smp");
        Side arena = new Side("arena");
        Player tom = smp.join("Tom");
        Player alex = arena.join("Alex");
        Player ghost = arena.join("Ghost");
        arena.vanish.vanish(ghost.getUniqueId());
        arena.presence.vanished(ghost.getUniqueId(), true);
        Player mod = smp.join("Mod");
        smp.vanish.maySeeVanished(mod.getUniqueId(), true);
        settle();

        assertThat(smp.reach.find(tom, "alex")).get().isEqualTo(
                new NetworkReach.Remote(alex.getUniqueId(), "Alex", "arena"));
        assertThat(smp.reach.find(tom, "Tom")).isEmpty();
        assertThat(smp.reach.find(tom, "Ghost")).isEmpty();
        assertThat(smp.reach.find(mod, "Ghost")).isPresent();
        assertThat(smp.reach.find(tom, "@a")).isEmpty();
        assertThat(smp.reach.find(tom, "Nobody")).isEmpty();
    }

    @Test
    @DisplayName("where somebody stands is asked of their server")
    void locates() throws Exception {
        Side smp = new Side("smp");
        Side arena = new Side("arena");
        Player alex = arena.join("Alex");
        settle();

        NetworkReach.Spot spot = smp.reach.locate(new NetworkReach.Remote(alex.getUniqueId(), "Alex", "arena"))
                .get(5, TimeUnit.SECONDS).orElseThrow();

        assertThat(spot).isEqualTo(new NetworkReach.Spot("world-arena", 1.5, 64, -3.5, 90f, 10f));
        assertThat(smp.reach.locate(new NetworkReach.Remote(UUID.randomUUID(), "Ghost", "arena"))
                .get(5, TimeUnit.SECONDS)).isEmpty();
        assertThat(smp.reach.locate(new NetworkReach.Remote(alex.getUniqueId(), "Alex", "dead"))
                .get(5, TimeUnit.SECONDS)).isEmpty();
    }

    @Test
    @DisplayName("the target's server is told who is coming and where from, and hands that over once")
    void spectateIsPrepared() throws Exception {
        Side smp = new Side("smp");
        Side arena = new Side("arena");
        Player alex = arena.join("Alex");
        UUID viewer = UUID.randomUUID();
        settle();
        NetworkReach.Spot from = new NetworkReach.Spot("world", 5, 70, 5, 0f, 0f);

        Optional<NetworkReach.Spot> there = smp.reach.prepareSpectate(
                new NetworkReach.Remote(alex.getUniqueId(), "Alex", "arena"), viewer, from, GameMode.CREATIVE, "smp")
                .get(5, TimeUnit.SECONDS);

        assertThat(there).isPresent();
        NetworkReach.Coming coming = arena.reach.takeComing(viewer).orElseThrow();
        assertThat(coming.target()).isEqualTo(alex.getUniqueId());
        assertThat(coming.fromServer()).isEqualTo("smp");
        assertThat(coming.from()).isEqualTo(from);
        assertThat(coming.mode()).isEqualTo(GameMode.CREATIVE);
        assertThat(arena.reach.takeComing(viewer)).isEmpty();
    }

    @Test
    @DisplayName("an expectation runs out after a minute, and can be cancelled")
    void expectationsEnd() throws Exception {
        Side smp = new Side("smp");
        Side arena = new Side("arena");
        Player alex = arena.join("Alex");
        UUID late = UUID.randomUUID();
        UUID cancelled = UUID.randomUUID();
        settle();
        NetworkReach.Remote target = new NetworkReach.Remote(alex.getUniqueId(), "Alex", "arena");
        NetworkReach.Spot from = new NetworkReach.Spot("world", 5, 70, 5, 0f, 0f);

        smp.reach.prepareSpectate(target, late, from, GameMode.SURVIVAL, "smp").get(5, TimeUnit.SECONDS);
        smp.reach.prepareSpectate(target, cancelled, from, GameMode.SURVIVAL, "smp").get(5, TimeUnit.SECONDS);
        smp.reach.cancelSpectate(target, cancelled);
        hub.drain();
        Thread.sleep(100);
        clock.addAndGet(NetworkReach.EXPECTED_FOR.toMillis() + 1);

        assertThat(arena.reach.takeComing(late)).isEmpty();
        assertThat(arena.reach.takeComing(cancelled)).isEmpty();
    }

    @Test
    @DisplayName("a spectate is not prepared for a target who is not there, nor for a stranger server")
    void notPrepared() throws Exception {
        Side smp = new Side("smp");
        Side arena = new Side("arena");
        UUID viewer = UUID.randomUUID();
        Player alex = arena.join("Alex");
        settle();
        NetworkReach.Spot from = new NetworkReach.Spot("world", 5, 70, 5, 0f, 0f);

        assertThat(smp.reach.prepareSpectate(new NetworkReach.Remote(UUID.randomUUID(), "Ghost", "arena"), viewer,
                from, GameMode.SURVIVAL, "smp").get(5, TimeUnit.SECONDS)).isEmpty();

        NetworkCalls stranger = new NetworkCalls(hub.bus("stranger"));
        open.add(stranger);
        var answer = stranger.ask("arena", NetworkReach.SPECTATE, Map.of("player", alex.getUniqueId().toString(),
                "viewer", viewer.toString(), "mode", "SURVIVAL", "returnServer", "stranger", "from.world", "w",
                "from.x", "1", "from.y", "1", "from.z", "1", "from.yaw", "0", "from.pitch", "0"),
                Duration.ofSeconds(5)).get(5, TimeUnit.SECONDS);
        assertThat(answer).isPresent();
        assertThat(answer.get()).containsEntry("found", "false");
        assertThat(arena.reach.takeComing(viewer)).isEmpty();

        var located = stranger.ask("arena", NetworkReach.LOCATE, Map.of("player", alex.getUniqueId().toString()),
                Duration.ofSeconds(5)).get(5, TimeUnit.SECONDS);
        assertThat(located.orElseThrow()).containsEntry("found", "false");
    }

    @Test
    @DisplayName("sending somebody over goes through the network's travel, to the place")
    void travels() throws Exception {
        Side smp = new Side("smp");
        Player tom = smp.join("Tom");
        NetworkReach.Spot to = new NetworkReach.Spot("world-arena", 1.5, 64, -3.5, 90f, 10f);

        assertThat(smp.reach.travel(tom, "arena", to).get(1, TimeUnit.SECONDS)).isTrue();

        verify(smp.travel).travel(eq(tom), eq("arena"), eq(Optional.of(new Arrivals.Destination("world-arena", 1.5, 64, -3.5, 90f, 10f))));
    }

    @Test
    @DisplayName("on a server of its own nobody is elsewhere and nothing travels")
    void alone() throws Exception {
        NetworkReach alone = new NetworkReach(mock(Server.class), new Vanish(mock(VanishSink.class)),
                player -> CompletableFuture.completedFuture(Optional.empty()), clock::get);

        assertThat(alone.isNetwork()).isFalse();
        assertThat(alone.self()).isEmpty();
        assertThat(alone.find(mock(Player.class), "Alex")).isEmpty();
        assertThat(alone.locate(new NetworkReach.Remote(UUID.randomUUID(), "A", "x")).get(1, TimeUnit.SECONDS)).isEmpty();
        assertThat(alone.travel(mock(Player.class), "x", new NetworkReach.Spot("w", 0, 0, 0, 0f, 0f))
                .get(1, TimeUnit.SECONDS)).isFalse();
        assertThat(alone.takeComing(UUID.randomUUID())).isEmpty();
    }
}
