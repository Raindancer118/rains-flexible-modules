package de.raindancer.modules.essentials.service;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.moderation.vanish.Vanish;
import de.raindancer.core.moderation.vanish.VanishSink;
import de.raindancer.core.platform.network.LocalBus;
import de.raindancer.core.platform.network.NetworkCalls;
import de.raindancer.core.platform.network.NetworkPlayers;
import de.raindancer.core.platform.network.NetworkServers;
import de.raindancer.core.platform.network.Presence;
import de.raindancer.core.testkit.TestDatabases;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** /list, /where and /seen over two servers of one network: Core's real presence, one database, one bus. */
@Tag("database")
class NetworkWhoTest {

    @TempDir
    Path folder;

    private final LocalBus.Hub hub = new LocalBus.Hub();
    private final List<Database> databases = new ArrayList<>();
    private final List<AutoCloseable> open = new ArrayList<>();

    private final class Side {
        final String id;
        final Server server = mock(Server.class);
        final Vanish vanish = new Vanish(mock(VanishSink.class));
        final List<Player> online = new ArrayList<>();
        final Presence presence;
        final NetworkServers servers;
        final NetworkWho who;

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
            servers.refresh();
            when(server.getOnlinePlayers()).thenAnswer(call -> List.copyOf(online));
            who = new NetworkWho(server, vanish, player -> CompletableFuture.completedFuture(
                    Optional.of(new NetworkWho.Spot("world-" + id, 10, 64, -20))));
            who.connect(players, calls);
            who.timeout(Duration.ofMillis(500));
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
            online.add(player);
            presence.joined(uuid, name, false);
            return player;
        }

        void vanish(Player player) {
            vanish.vanish(player.getUniqueId());
            presence.vanished(player.getUniqueId(), true);
        }

        /** A staff member who may see the vanished, and one who may not. */
        Player staff(String name) {
            Player player = join(name);
            vanish.maySeeVanished(player.getUniqueId(), true);
            return player;
        }
    }

    private final List<Side> sides = new ArrayList<>();

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
    @DisplayName("/list shows everybody on the network grouped by server, this server first")
    void listGroupedByServer() {
        Side smp = new Side("smp");
        Side arena = new Side("arena");
        Player tom = smp.join("Tom");
        smp.join("Zed");
        arena.join("Alex");
        settle();

        List<NetworkWho.Group> groups = smp.who.everybody(tom);

        assertThat(groups).extracting(NetworkWho.Group::server).containsExactly("smp", "arena");
        assertThat(groups.get(0).self()).isTrue();
        assertThat(groups.get(0).players()).extracting(NetworkWho.Listed::name).containsExactly("Tom", "Zed");
        assertThat(groups.get(1).players()).extracting(NetworkWho.Listed::name).containsExactly("Alex");
        assertThat(arena.who.everybody(arena.online.getFirst()).getFirst().server()).isEqualTo("arena");
    }

    @Test
    @DisplayName("a vanished player is left out of /list for whoever may not see them, here and elsewhere")
    void listHidesTheVanished() {
        Side smp = new Side("smp");
        Side arena = new Side("arena");
        Player tom = smp.join("Tom");
        Player ghostHere = smp.join("GhostHere");
        Player ghostThere = arena.join("GhostThere");
        arena.join("Alex");
        smp.vanish(ghostHere);
        arena.vanish(ghostThere);
        settle();

        List<NetworkWho.Group> forTom = smp.who.everybody(tom);
        assertThat(forTom.stream().flatMap(group -> group.players().stream()).map(NetworkWho.Listed::name))
                .containsExactlyInAnyOrder("Tom", "Alex");

        Player mod = smp.staff("Mod");
        settle();
        List<NetworkWho.Listed> forMod = smp.who.everybody(mod).stream().flatMap(group -> group.players().stream()).toList();
        assertThat(forMod).extracting(NetworkWho.Listed::name)
                .containsExactlyInAnyOrder("Tom", "Mod", "GhostHere", "Alex", "GhostThere");
        assertThat(forMod).filteredOn(NetworkWho.Listed::vanished).extracting(NetworkWho.Listed::name)
                .containsExactlyInAnyOrder("GhostHere", "GhostThere");
    }

    @Test
    @DisplayName("/where finds somebody on another server by name, any case, and not a vanished one")
    void whereFindsElsewhere() {
        Side smp = new Side("smp");
        Side arena = new Side("arena");
        Player tom = smp.join("Tom");
        Player alex = arena.join("Alex");
        Player ghost = arena.join("Ghost");
        arena.vanish(ghost);
        settle();

        Optional<NetworkWho.Found> found = smp.who.find(tom, "aLeX");
        assertThat(found).isPresent();
        assertThat(found.get().server()).isEqualTo("arena");
        assertThat(found.get().self()).isFalse();
        assertThat(found.get().id()).isEqualTo(alex.getUniqueId());
        assertThat(smp.who.find(tom, "Ghost")).isEmpty();
        assertThat(smp.who.find(tom, "Nobody")).isEmpty();
        assertThat(smp.who.find(tom, "@a")).isEmpty();
        assertThat(smp.who.find(tom, "Tom")).get().extracting(NetworkWho.Found::self).isEqualTo(true);
    }

    @Test
    @DisplayName("staff who may see the vanished find them with /where; the place is asked of their server")
    void whereForStaff() throws Exception {
        Side smp = new Side("smp");
        Side arena = new Side("arena");
        Player mod = smp.staff("Mod");
        Player ghost = arena.join("Ghost");
        arena.vanish(ghost);
        settle();

        NetworkWho.Found found = smp.who.find(mod, "Ghost").orElseThrow();
        assertThat(found.vanished()).isTrue();
        NetworkWho.Spot spot = smp.who.locate(found).get(5, TimeUnit.SECONDS).orElseThrow();

        assertThat(spot).isEqualTo(new NetworkWho.Spot("world-arena", 10, 64, -20));
    }

    @Test
    @DisplayName("a server answers a location only for a player it hosts, and only to a server of the network")
    void locationOnlyForOurOwn() throws Exception {
        Side smp = new Side("smp");
        Side arena = new Side("arena");
        smp.join("Tom");
        Player alex = arena.join("Alex");
        settle();

        NetworkWho.Found wrongServer = new NetworkWho.Found(alex.getUniqueId(), "Alex", "smp", false, false);
        assertThat(smp.who.locate(wrongServer).get(5, TimeUnit.SECONDS)).isEmpty();

        NetworkWho.Found nobody = new NetworkWho.Found(UUID.randomUUID(), "Ghost", "arena", false, false);
        assertThat(smp.who.locate(nobody).get(5, TimeUnit.SECONDS)).isEmpty();

        NetworkCalls stranger = new NetworkCalls(hub.bus("stranger"));
        open.add(stranger);
        var answer = stranger.ask("arena", NetworkWho.WHERE, java.util.Map.of("player", alex.getUniqueId().toString()),
                Duration.ofSeconds(5)).get(5, TimeUnit.SECONDS);
        assertThat(answer).isPresent();
        assertThat(answer.get()).containsEntry("found", "false");
    }

    @Test
    @DisplayName("/seen learns somebody is online on another server, vanished ones only for those who may see them")
    void seenElsewhere() {
        Side smp = new Side("smp");
        Side arena = new Side("arena");
        Player tom = smp.join("Tom");
        Player alex = arena.join("Alex");
        Player ghost = arena.join("Ghost");
        arena.vanish(ghost);
        Player mod = smp.staff("Mod");
        settle();

        assertThat(smp.who.elsewhere(tom, alex.getUniqueId())).get().extracting(NetworkWho.Found::server).isEqualTo("arena");
        assertThat(smp.who.elsewhere(tom, ghost.getUniqueId())).isEmpty();
        assertThat(smp.who.elsewhere(mod, ghost.getUniqueId())).isPresent();
        assertThat(smp.who.elsewhere(tom, tom.getUniqueId())).isEmpty();
        assertThat(smp.who.namesElsewhere(tom)).containsExactly("Alex");
        assertThat(smp.who.namesElsewhere(mod)).containsExactlyInAnyOrder("Alex", "Ghost");
    }

    @Test
    @DisplayName("on a server of its own everything is about this server, and nothing is asked of anybody")
    void alone() {
        Server server = mock(Server.class);
        Vanish vanish = new Vanish(mock(VanishSink.class));
        Player tom = mock(Player.class);
        UUID id = UUID.nameUUIDFromBytes("Tom".getBytes());
        when(tom.getUniqueId()).thenReturn(id);
        when(tom.getName()).thenReturn("Tom");
        when(tom.isOnline()).thenReturn(true);
        when(server.getOnlinePlayers()).thenAnswer(call -> List.of(tom));
        when(server.getPlayerExact("Tom")).thenReturn(tom);
        NetworkWho who = new NetworkWho(server, vanish, player -> CompletableFuture.completedFuture(Optional.empty()));

        assertThat(who.isNetwork()).isFalse();
        assertThat(who.everybody(tom)).hasSize(1);
        assertThat(who.everybody(tom).getFirst().players()).extracting(NetworkWho.Listed::name).containsExactly("Tom");
        assertThat(who.find(tom, "Tom")).isPresent();
        assertThat(who.find(tom, "Alex")).isEmpty();
        assertThat(who.elsewhere(tom, UUID.randomUUID())).isEmpty();
        assertThat(who.namesElsewhere(tom)).isEmpty();
    }
}
