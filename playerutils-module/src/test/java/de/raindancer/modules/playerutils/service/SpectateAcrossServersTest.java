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
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.playerutils.PlayerUtilsSettings;
import de.raindancer.modules.playerutils.store.SpectateReturns;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** /spectate across servers: told to the target's server, travelled, resumed on arrival, and the way back leads home. */
@Tag("database")
class SpectateAcrossServersTest {

    @TempDir
    Path folder;

    private final LocalBus.Hub hub = new LocalBus.Hub();
    private final List<Database> databases = new ArrayList<>();
    private final List<AutoCloseable> open = new ArrayList<>();
    private final List<Side> sides = new ArrayList<>();

    private final class Side {
        final String id;
        final Server server = mock(Server.class);
        final Messages messages = mock(Messages.class);
        final NetworkTravel travel = mock(NetworkTravel.class);
        final Presence presence;
        final NetworkServers servers;
        final NetworkReach reach;
        final SpectateService spectate;

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
            reach = new NetworkReach(server, new Vanish(mock(VanishSink.class)), player -> {
                Location at = player.getLocation();
                return CompletableFuture.completedFuture(Optional.of(new NetworkReach.Spot(at.getWorld().getName(),
                        at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch())));
            }, System::currentTimeMillis);
            when(travel.travel(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(true));
            reach.connect(players, calls, travel);
            reach.timeout(Duration.ofMillis(500));
            spectate = new SpectateService(mock(Plugin.class), server, messages, PlayerUtilsSettings.DEFAULTS);
            spectate.reach(reach);
            sides.add(this);
        }

        /** A player of this server standing at {@code x} in this server's world, with a data container that remembers. */
        Player join(String name, double x, GameMode mode) {
            Player player = mock(Player.class);
            UUID uuid = UUID.nameUUIDFromBytes(name.getBytes());
            World world = mock(World.class);
            when(world.getName()).thenReturn("world-" + id);
            when(player.getName()).thenReturn(name);
            when(player.getUniqueId()).thenReturn(uuid);
            when(player.isOnline()).thenReturn(true);
            when(player.getWorld()).thenReturn(world);
            when(player.getLocation()).thenReturn(new Location(world, x, 70, 5, 90, 0));
            when(player.getGameMode()).thenReturn(mode);
            when(player.teleportAsync(any(Location.class))).thenReturn(CompletableFuture.completedFuture(false));
            AtomicReference<String> stored = new AtomicReference<>();
            PersistentDataContainer data = mock(PersistentDataContainer.class);
            when(player.getPersistentDataContainer()).thenReturn(data);
            org.mockito.Mockito.doAnswer(call -> {
                stored.set(call.getArgument(2));
                return null;
            }).when(data).set(any(org.bukkit.NamespacedKey.class), eq(PersistentDataType.STRING), any(String.class));
            when(data.get(any(org.bukkit.NamespacedKey.class), eq(PersistentDataType.STRING))).thenAnswer(call -> stored.get());
            org.mockito.Mockito.doAnswer(call -> {
                stored.set(null);
                return null;
            }).when(data).remove(any(org.bukkit.NamespacedKey.class));
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
    @DisplayName("the staff member is sent to the target's place, and the target's server is told whom to expect and from where")
    void sentToTheTarget() throws Exception {
        Side smp = new Side("smp");
        Side arena = new Side("arena");
        Player staff = smp.join("Staff", 100, GameMode.CREATIVE);
        Player alex = arena.join("Alex", 7, GameMode.SURVIVAL);
        settle();

        boolean moved = smp.spectate.startAcross(staff, smp.reach.find(staff, "Alex").orElseThrow())
                .get(5, TimeUnit.SECONDS);

        assertThat(moved).isTrue();
        verify(smp.travel).travel(eq(staff), eq("arena"),
                eq(Optional.of(new Arrivals.Destination("world-arena", 7, 70, 5, 90f, 0f))));
        verify(smp.messages).send(staff, "playerutils.spectate.travelling", "player", "Alex", "server", "arena");
        NetworkReach.Coming coming = arena.reach.takeComing(staff.getUniqueId()).orElseThrow();
        assertThat(coming.target()).isEqualTo(alex.getUniqueId());
        assertThat(coming.fromServer()).isEqualTo("smp");
        assertThat(coming.from()).isEqualTo(new NetworkReach.Spot("world-smp", 100, 70, 5, 90f, 0f));
        assertThat(coming.mode()).isEqualTo(GameMode.CREATIVE);
    }

    @Test
    @DisplayName("when the hop fails the staff member stays, is told, and the target's server forgets them")
    void hopFails() throws Exception {
        Side smp = new Side("smp");
        Side arena = new Side("arena");
        Player staff = smp.join("Staff", 100, GameMode.SURVIVAL);
        arena.join("Alex", 7, GameMode.SURVIVAL);
        settle();
        when(smp.travel.travel(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(false));

        boolean moved = smp.spectate.startAcross(staff, smp.reach.find(staff, "Alex").orElseThrow())
                .get(5, TimeUnit.SECONDS);
        hub.drain();
        Thread.sleep(150);

        assertThat(moved).isFalse();
        verify(smp.messages).send(staff, "playerutils.spectate.elsewhere-failed", "player", "Alex", "server", "arena");
        assertThat(arena.reach.takeComing(staff.getUniqueId())).isEmpty();
    }

    @Test
    @DisplayName("a target who left meanwhile is not travelled to")
    void targetGone() throws Exception {
        Side smp = new Side("smp");
        Side arena = new Side("arena");
        Player staff = smp.join("Staff", 100, GameMode.SURVIVAL);
        Player alex = arena.join("Alex", 7, GameMode.SURVIVAL);
        settle();
        NetworkReach.Remote remote = smp.reach.find(staff, "Alex").orElseThrow();
        when(arena.server.getPlayer(alex.getUniqueId())).thenReturn(null);

        assertThat(smp.spectate.startAcross(staff, remote).get(5, TimeUnit.SECONDS)).isFalse();

        verify(smp.travel, never()).travel(any(), any(), any());
        verify(smp.messages).send(staff, "playerutils.spectate.elsewhere-failed", "player", "Alex", "server", "arena");
    }

    @Test
    @DisplayName("on arrival the watching resumes with the way back to the server they left; stopping goes there")
    void resumedAndSentBack() throws Exception {
        Side smp = new Side("smp");
        Side arena = new Side("arena");
        Player staff = smp.join("Staff", 100, GameMode.CREATIVE);
        Player alex = arena.join("Alex", 7, GameMode.SURVIVAL);
        settle();
        smp.spectate.startAcross(staff, smp.reach.find(staff, "Alex").orElseThrow()).get(5, TimeUnit.SECONDS);

        // The same staff member, now arrived on arena.
        Player arrived = arena.join("Staff", 7, GameMode.SURVIVAL);
        NetworkReach.Coming coming = arena.reach.takeComing(arrived.getUniqueId()).orElseThrow();
        arena.spectate.resume(arrived, coming);

        verify(arrived).setGameMode(GameMode.SPECTATOR);
        verify(arena.messages).send(arrived, "playerutils.spectate.started", "player", "Alex");
        SpectateReturns.Return back = SpectateReturns.of(arrived).orElseThrow();
        assertThat(back.server()).isEqualTo("smp");
        assertThat(back.mode()).isEqualTo(GameMode.CREATIVE);
        assertThat(back.watching()).isEqualTo(alex.getUniqueId());
        assertThat(arena.spectate.isSpectating(arrived)).isTrue();

        assertThat(arena.spectate.stop(arrived)).isTrue();

        verify(arrived).setGameMode(GameMode.CREATIVE);
        verify(arena.travel).travel(eq(arrived), eq("smp"),
                eq(Optional.of(new Arrivals.Destination("world-smp", 100, 70, 5, 90f, 0f))));
        assertThat(arena.spectate.isSpectating(arrived)).isFalse();
    }

    @Test
    @DisplayName("somebody who followed their target on to a third server still goes back to the first")
    void thirdServer() throws Exception {
        Side smp = new Side("smp");
        Side arena = new Side("arena");
        Side lobby = new Side("lobby");
        Player staff = smp.join("Staff", 100, GameMode.SURVIVAL);
        arena.join("Alex", 7, GameMode.SURVIVAL);
        lobby.join("Bob", 9, GameMode.SURVIVAL);
        settle();
        smp.spectate.startAcross(staff, smp.reach.find(staff, "Alex").orElseThrow()).get(5, TimeUnit.SECONDS);
        Player onArena = arena.join("Staff", 7, GameMode.SURVIVAL);
        arena.spectate.resume(onArena, arena.reach.takeComing(onArena.getUniqueId()).orElseThrow());
        settle();

        arena.spectate.startAcross(onArena, arena.reach.find(onArena, "Bob").orElseThrow()).get(5, TimeUnit.SECONDS);

        NetworkReach.Coming coming = lobby.reach.takeComing(onArena.getUniqueId()).orElseThrow();
        assertThat(coming.fromServer()).isEqualTo("smp");
        assertThat(coming.from()).isEqualTo(new NetworkReach.Spot("world-smp", 100, 70, 5, 90f, 0f));
        assertThat(coming.mode()).isEqualTo(GameMode.SURVIVAL);
    }

    @Test
    @DisplayName("a target who left before arrival leaves the staff member where they landed, told so, not spectating")
    void targetLeftBeforeArrival() throws Exception {
        Side smp = new Side("smp");
        Side arena = new Side("arena");
        Player staff = smp.join("Staff", 100, GameMode.SURVIVAL);
        Player alex = arena.join("Alex", 7, GameMode.SURVIVAL);
        settle();
        smp.spectate.startAcross(staff, smp.reach.find(staff, "Alex").orElseThrow()).get(5, TimeUnit.SECONDS);
        Player arrived = arena.join("Staff", 7, GameMode.SURVIVAL);
        when(arena.server.getPlayer(alex.getUniqueId())).thenReturn(null);

        arena.spectate.resume(arrived, arena.reach.takeComing(arrived.getUniqueId()).orElseThrow());

        verify(arena.messages).send(arrived, "playerutils.spectate.target-gone");
        verify(arrived, never()).setGameMode(GameMode.SPECTATOR);
        assertThat(arena.spectate.isSpectating(arrived)).isFalse();
    }

    @Test
    @DisplayName("a way back on this server is used as it always was, with nothing sent anywhere")
    void localWayBack() {
        Side smp = new Side("smp");
        Player staff = smp.join("Staff", 100, GameMode.SURVIVAL);
        Location here = staff.getLocation();
        when(smp.server.getWorlds()).thenReturn(List.of(here.getWorld()));
        SpectateReturns.remember(staff, SpectateReturns.from(staff, UUID.randomUUID()));

        try (var bukkit = org.mockito.Mockito.mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(() -> org.bukkit.Bukkit.getWorld("world-smp")).thenReturn(here.getWorld());
            assertThat(smp.spectate.stop(staff)).isTrue();
        }

        verify(smp.travel, never()).travel(any(), any(), any());
        verify(smp.messages).send(staff, "playerutils.spectate.stopped");
    }
}
