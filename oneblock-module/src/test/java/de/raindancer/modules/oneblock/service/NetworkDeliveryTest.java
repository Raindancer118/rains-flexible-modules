package de.raindancer.modules.oneblock.service;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.network.LocalBus;
import de.raindancer.core.platform.network.NetworkPlayers;
import de.raindancer.core.platform.network.NetworkServers;
import de.raindancer.core.platform.network.Presence;
import de.raindancer.core.testkit.TestDatabases;
import de.raindancer.core.ui.messages.Messages;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Lines for, and the finding of, players on another server — through the network's presence, on two "servers". */
@Tag("database")
class NetworkDeliveryTest {

    private static final UUID BO = UUID.fromString("00000000-0000-0000-0000-0000000000b0");

    @TempDir
    Path folder;

    private final LocalBus.Hub hub = new LocalBus.Hub();
    private final List<Database> databases = new ArrayList<>();
    private final List<String> said = new CopyOnWriteArrayList<>();

    private record Server2(NetworkServers servers, Presence presence, NetworkPlayers players) {
    }

    private Server2 server(String id, Set<UUID> online) {
        Database database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE);
        databases.add(database);
        NetworkServers servers = new NetworkServers(database, id, "default", System::currentTimeMillis);
        Presence presence = new Presence(database, servers, System::currentTimeMillis);
        LocalBus bus = hub.bus(id);
        presence.connect(bus);
        NetworkPlayers players = new NetworkPlayers(presence, servers, bus, (player, key, values) -> {
            if (!online.contains(player)) {
                return false;
            }
            said.add(id + " " + key + values);
            return true;
        });
        servers.heartbeat(id, online.size());
        return new Server2(servers, presence, players);
    }

    @AfterEach
    void close() {
        databases.forEach(Database::close);
    }

    private NetworkDelivery delivery(NetworkPlayers players) {
        return new NetworkDelivery(mock(Plugin.class), mock(Server.class), mock(Messages.class),
                () -> null, () -> Optional.of(players), player -> false);
    }

    @Test
    @DisplayName("a typed name is found on another server with its id and the server, and a line reaches them there")
    void findsAndTells() throws Exception {
        Server2 island = server("oneblock", Set.of());
        Server2 hubServer = server("hub", Set.of(BO));
        hubServer.presence().joined(BO, "Bo", false);
        island.servers().refresh();
        island.presence().refreshAll();
        NetworkDelivery delivery = delivery(island.players());

        var found = delivery.resolve("bo").get();
        assertThat(found).hasValueSatisfying(who -> {
            assertThat(who.id()).isEqualTo(BO);
            assertThat(who.name()).isEqualTo("Bo");
            assertThat(who.server()).isEqualTo("hub");
        });

        delivery.tell("Bo", "oneblock.invite.accepted", "player", "Ada");
        hub.drain();

        assertThat(said).containsExactly("hub oneblock.invite.accepted[player, Ada]");
    }

    @Test
    @DisplayName("a vanished player is nobody, and a line for nobody is not sent")
    void vanishedIsNobody() throws Exception {
        Server2 island = server("oneblock", Set.of());
        Server2 hubServer = server("hub", Set.of(BO));
        hubServer.presence().joined(BO, "Bo", true);
        island.servers().refresh();
        island.presence().refreshAll();
        NetworkDelivery delivery = delivery(island.players());

        assertThat(delivery.resolve("Bo").get()).isEmpty();
        delivery.tell("Bo", "oneblock.invite.accepted");
        hub.drain();

        assertThat(said).isEmpty();
    }
}
