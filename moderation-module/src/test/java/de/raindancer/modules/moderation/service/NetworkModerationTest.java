package de.raindancer.modules.moderation.service;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.network.LocalBus;
import de.raindancer.core.platform.network.NetworkCalls;
import de.raindancer.core.platform.network.NetworkPlayers;
import de.raindancer.core.platform.network.NetworkServers;
import de.raindancer.core.platform.network.Presence;
import de.raindancer.core.testkit.TestDatabases;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Kicking, finding and telling somebody on another server: Core's real presence and calls over one bus. */
@Tag("database")
class NetworkModerationTest {

    @TempDir
    Path folder;

    private final LocalBus.Hub hub = new LocalBus.Hub();
    private final List<Database> databases = new ArrayList<>();
    private final List<AutoCloseable> open = new ArrayList<>();
    private final List<Side> sides = new ArrayList<>();

    private final class Side {
        final String id;
        final Server server = mock(Server.class);
        final List<String> kicked = new CopyOnWriteArrayList<>();
        final List<String> told = new CopyOnWriteArrayList<>();
        final Presence presence;
        final NetworkServers servers;
        final NetworkModeration moderation;

        Side(String id) {
            this.id = id;
            Database database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE);
            databases.add(database);
            servers = new NetworkServers(database, id, "default", System::currentTimeMillis);
            presence = new Presence(database, servers, System::currentTimeMillis);
            LocalBus bus = hub.bus(id);
            presence.connect(bus);
            NetworkPlayers players = new NetworkPlayers(presence, servers, bus, (player, key, values) -> {
                if (server.getPlayer(player) == null) {
                    return false;
                }
                told.add(key + values);
                return true;
            });
            NetworkCalls calls = new NetworkCalls(bus);
            open.add(calls);
            servers.heartbeat(id, 0);
            moderation = new NetworkModeration(server, (player, reason, ban) -> {
                kicked.add(player.getName() + ":" + reason + ":" + ban);
                return CompletableFuture.completedFuture(true);
            });
            moderation.connect(players, calls);
            moderation.timeout(Duration.ofMillis(500));
            sides.add(this);
        }

        Player join(String name) {
            Player player = mock(Player.class);
            UUID uuid = UUID.nameUUIDFromBytes(name.getBytes());
            when(player.getName()).thenReturn(name);
            when(player.getUniqueId()).thenReturn(uuid);
            when(server.getPlayer(uuid)).thenReturn(player);
            presence.joined(uuid, name, false);
            return player;
        }

        void vanish(Player player) {
            presence.vanished(player.getUniqueId(), true);
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

    private static void waitFor(java.util.function.BooleanSupplier condition) {
        long until = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < until) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static boolean kicked(NetworkModeration moderation, NetworkModeration.There there, String reason, boolean ban)
            throws Exception {
        return moderation.kick(there, reason, ban).get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("a kick for somebody on another server is done there, with the reason, and says so")
    void kickedOnTheirServer() throws Exception {
        Side smp = new Side("smp");
        Side arena = new Side("arena");
        Player alex = arena.join("Alex");
        settle();

        NetworkModeration.There there = smp.moderation.whereIs(alex.getUniqueId(), false).orElseThrow();

        assertThat(there.server()).isEqualTo("arena");
        assertThat(kicked(smp.moderation, there, "spamming", false)).isTrue();
        assertThat(arena.kicked).containsExactly("Alex:spamming:false");
        assertThat(smp.kicked).isEmpty();
    }

    @Test
    @DisplayName("a server kicks only a player it hosts: one that moved on meanwhile is not kicked, and nothing hangs")
    void notKickedWhenNotThere() throws Exception {
        Side smp = new Side("smp");
        Side arena = new Side("arena");
        Player alex = arena.join("Alex");
        settle();
        NetworkModeration.There there = smp.moderation.whereIs(alex.getUniqueId(), false).orElseThrow();
        when(arena.server.getPlayer(alex.getUniqueId())).thenReturn(null);

        assertThat(kicked(smp.moderation, there, "x", false)).isFalse();
        assertThat(arena.kicked).isEmpty();

        NetworkModeration.There nowhere = new NetworkModeration.There(alex.getUniqueId(), "Alex", "dead-server", false);
        assertThat(kicked(smp.moderation, nowhere, "x", false)).isFalse();
    }

    @Test
    @DisplayName("a kick is only accepted from a server of the network")
    void onlyFromTheNetwork() throws Exception {
        Side smp = new Side("smp");
        Side arena = new Side("arena");
        Player alex = arena.join("Alex");
        settle();

        NetworkCalls stranger = new NetworkCalls(hub.bus("stranger"));
        open.add(stranger);
        var answer = stranger.ask("arena", NetworkModeration.KICK, Map.of("player", alex.getUniqueId().toString(),
                "reason", "x", "ban", "false"), Duration.ofSeconds(5)).get(5, TimeUnit.SECONDS);

        assertThat(answer).isPresent();
        assertThat(answer.get()).containsEntry("kicked", "false");
        assertThat(arena.kicked).isEmpty();
    }

    @Test
    @DisplayName("a vanished player is found only by staff who may see them; a ban reaches them wherever they are")
    void vanishedStaysHidden() throws Exception {
        Side smp = new Side("smp");
        Side arena = new Side("arena");
        Player ghost = arena.join("Ghost");
        arena.vanish(ghost);
        settle();

        assertThat(smp.moderation.whereIs(ghost.getUniqueId(), false)).isEmpty();
        assertThat(smp.moderation.whereIs(ghost.getUniqueId(), true)).isPresent();
        assertThat(smp.moderation.everyoneElse(false)).isEmpty();
        assertThat(smp.moderation.everyoneElse(true)).extracting(NetworkModeration.There::name).containsExactly("Ghost");

        smp.moderation.kickAnywhere(ghost.getUniqueId(), "banned", true);
        hub.drain();
        waitFor(() -> !arena.kicked.isEmpty());
        assertThat(arena.kicked).containsExactly("Ghost:banned:true");
    }

    @Test
    @DisplayName("staff elsewhere are the roster's people on other servers")
    void staffElsewhere() {
        Side smp = new Side("smp");
        Side arena = new Side("arena");
        Player mod = arena.join("Mod");
        arena.join("Alex");
        smp.join("LocalStaff");
        settle();

        assertThat(smp.moderation.staffElsewhere(false, mod.getUniqueId()::equals))
                .extracting(NetworkModeration.There::name).containsExactly("Mod");
    }

    @Test
    @DisplayName("a word to somebody on another server is said there, and reports when nobody could be told")
    void tellsWhereverTheyAre() {
        Side smp = new Side("smp");
        Side arena = new Side("arena");
        Player alex = arena.join("Alex");
        settle();

        assertThat(smp.moderation.tell(alex.getUniqueId(), "moderation.you-were-muted", "reason", "spam")).isTrue();
        hub.drain();
        waitFor(() -> !arena.told.isEmpty());
        assertThat(arena.told.getFirst()).startsWith("moderation.you-were-muted");

        assertThat(smp.moderation.tell(UUID.randomUUID(), "moderation.you-were-muted")).isFalse();
    }

    @Test
    @DisplayName("on a server of its own nobody is elsewhere, and nothing is asked")
    void alone() throws Exception {
        NetworkModeration alone = new NetworkModeration(mock(Server.class), (player, reason, ban) ->
                CompletableFuture.completedFuture(true));

        assertThat(alone.isNetwork()).isFalse();
        assertThat(alone.whereIs(UUID.randomUUID(), true)).isEmpty();
        assertThat(alone.everyoneElse(true)).isEmpty();
        assertThat(alone.tell(UUID.randomUUID(), "x")).isFalse();
        assertThat(alone.kick(new NetworkModeration.There(UUID.randomUUID(), "A", "arena", false), "x", false)
                .get(1, TimeUnit.SECONDS)).isFalse();
        alone.kickAnywhere(UUID.randomUUID(), "x", true);
    }
}
