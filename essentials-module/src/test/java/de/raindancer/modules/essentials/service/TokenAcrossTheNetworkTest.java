package de.raindancer.modules.essentials.service;

import de.raindancer.core.content.items.ItemFactory;
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
import de.raindancer.core.ui.messages.Messages;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** /token for a player on another server: asked of their server, which hands it over only if they are there. */
@Tag("database")
class TokenAcrossTheNetworkTest {

    @TempDir
    Path folder;

    private final LocalBus.Hub hub = new LocalBus.Hub();
    private final List<Database> databases = new ArrayList<>();
    private final List<AutoCloseable> open = new ArrayList<>();
    private final List<Side> sides = new ArrayList<>();

    private final class Side {
        final Server server = mock(Server.class);
        final Messages messages = mock(Messages.class);
        final Vanish vanish = new Vanish(mock(VanishSink.class));
        final Presence presence;
        final NetworkServers servers;
        final NetworkWho who;
        final SkyTokenService tokens;
        final NetworkCalls calls;

        Side(String id) {
            Database database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE);
            databases.add(database);
            servers = new NetworkServers(database, id, "default", System::currentTimeMillis);
            presence = new Presence(database, servers, System::currentTimeMillis);
            LocalBus bus = hub.bus(id);
            presence.connect(bus);
            NetworkPlayers players = new NetworkPlayers(presence, servers, bus, (player, key, values) -> false);
            calls = new NetworkCalls(bus);
            open.add(calls);
            servers.heartbeat(id, 0);
            servers.refresh();
            who = new NetworkWho(server, vanish, player -> java.util.concurrent.CompletableFuture
                    .completedFuture(Optional.empty()));
            who.connect(players, calls);
            ItemFactory factory = mock(ItemFactory.class);
            when(factory.create(any(), anyInt())).thenReturn(Optional.of(mock(ItemStack.class)));
            tokens = new SkyTokenService(mock(Plugin.class), server, messages, factory);
            tokens.connect(players, calls);
            tokens.timeout(Duration.ofMillis(500));
            sides.add(this);
        }

        Player join(String name) {
            Player player = mock(Player.class);
            UUID uuid = UUID.nameUUIDFromBytes(name.getBytes());
            when(player.getName()).thenReturn(name);
            when(player.getUniqueId()).thenReturn(uuid);
            when(player.isOnline()).thenReturn(true);
            when(player.getInventory()).thenReturn(mock(PlayerInventory.class));
            when(server.getPlayerExact(name)).thenReturn(player);
            when(server.getPlayer(uuid)).thenReturn(player);
            presence.joined(uuid, name, false);
            return player;
        }

        void leave(Player player) {
            when(server.getPlayer(player.getUniqueId())).thenReturn(null);
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
    @DisplayName("the player's server hands the tokens over, tells them, and the giver hears who got them")
    void handedOverElsewhere() throws Exception {
        Side smp = new Side("smp");
        Side arena = new Side("arena");
        Player op = smp.join("Op");
        Player alex = arena.join("Alex");
        settle();
        NetworkWho.Found found = smp.who.find(op, "alex").orElseThrow();

        boolean given = smp.tokens.giveElsewhere(op, found, SkyTokenService.Kind.SKY, 2).get(5, TimeUnit.SECONDS);

        assertThat(given).isTrue();
        verify(alex.getInventory()).addItem(any(ItemStack[].class));
        verify(arena.messages).send(alex, "essentials.tokens.received", "amount", 2, "token", "Clear Sky Token");
        verify(smp.messages).send(op, "essentials.tokens.given", "amount", 2, "token", "Clear Sky Token", "player", "Alex");
    }

    @Test
    @DisplayName("somebody who left that server meanwhile gets nothing, and the giver is told they are not online")
    void goneMeanwhile() throws Exception {
        Side smp = new Side("smp");
        Side arena = new Side("arena");
        Player op = smp.join("Op");
        Player alex = arena.join("Alex");
        settle();
        NetworkWho.Found found = smp.who.find(op, "Alex").orElseThrow();
        arena.leave(alex);

        boolean given = smp.tokens.giveElsewhere(op, found, SkyTokenService.Kind.DAWN, 1).get(5, TimeUnit.SECONDS);

        assertThat(given).isFalse();
        verify(alex.getInventory(), never()).addItem(any(ItemStack[].class));
        verify(smp.messages).send(op, "essentials.tokens.not-online", "player", "Alex");
    }

    @Test
    @DisplayName("a vanished player on another server cannot be found by an op who may not see them")
    void vanishedStaysHidden() {
        Side smp = new Side("smp");
        Side arena = new Side("arena");
        Player op = smp.join("Op");
        Player ghost = arena.join("Ghost");
        arena.vanish.vanish(ghost.getUniqueId());
        arena.presence.vanished(ghost.getUniqueId(), true);
        settle();

        assertThat(smp.who.find(op, "Ghost")).isEmpty();
    }

    @Test
    @DisplayName("a question from something that is not a server of the network is refused")
    void strangersRefused() throws Exception {
        Side arena = new Side("arena");
        Player alex = arena.join("Alex");
        settle();
        NetworkCalls rogue = new NetworkCalls(hub.bus("rogue"));
        open.add(rogue);

        Optional<Map<String, String>> answer = rogue.ask("arena", SkyTokenService.GIVE, Map.of(
                "player", alex.getUniqueId().toString(), "kind", "SKY", "amount", "64"), Duration.ofSeconds(2))
                .get(5, TimeUnit.SECONDS);

        assertThat(answer).hasValueSatisfying(reply -> assertThat(reply).containsEntry("ok", "false"));
        verify(alex.getInventory(), never()).addItem(any(ItemStack[].class));
    }
}
