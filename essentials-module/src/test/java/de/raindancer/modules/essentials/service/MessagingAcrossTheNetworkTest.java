package de.raindancer.modules.essentials.service;

import de.raindancer.core.moderation.vanish.Vanish;
import de.raindancer.core.moderation.vanish.VanishSink;
import de.raindancer.core.platform.network.LocalBus;
import de.raindancer.core.platform.network.NetworkCalls;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.essentials.EssentialsSettings;
import de.raindancer.modules.essentials.store.EssentialsStore;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** /msg and /r between servers of a network: found by name anywhere, delivered over the network's calls. */
class MessagingAcrossTheNetworkTest {

    @TempDir
    Path folder;

    private final LocalBus.Hub hub = new LocalBus.Hub();
    private final List<AutoCloseable> open = new ArrayList<>();

    /** One server: its own messaging, players, vanish and view of who is where. */
    private final class Server {
        final Messages messages = mock(Messages.class);
        final Chat chat = mock(Chat.class);
        final Vanish vanish = new Vanish(mock(VanishSink.class));
        final Map<String, Player> online = new java.util.concurrent.ConcurrentHashMap<>();
        final Map<String, String> elsewhere = new java.util.concurrent.ConcurrentHashMap<>();
        final EssentialsStore store;
        final MessagingService messaging;

        Server(String id, boolean connected) {
            store = new EssentialsStore(folder.resolve(id));
            when(messages.raw(any())).thenAnswer(call -> call.getArgument(0));
            messaging = new MessagingService(store, messages, chat, vanish, EssentialsSettings.DEFAULTS);
            if (connected) {
                NetworkCalls calls = new NetworkCalls(hub.bus(id));
                open.add(calls);
                messaging.connectNetwork(new MessagingService.Directory() {
                    @Override
                    public Optional<MessagingService.Remote> find(String typed) {
                        return elsewhere.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(typed))
                                .findFirst().map(e -> new MessagingService.Remote(e.getKey(), e.getValue()));
                    }

                    @Override
                    public Set<String> names() {
                        return Set.copyOf(elsewhere.keySet());
                    }
                }, calls, name -> online.get(name));
                messaging.acrossTimeout(Duration.ofMillis(400));
            }
        }

        Player join(String name) {
            Player who = mock(Player.class);
            when(who.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes()));
            when(who.getName()).thenReturn(name);
            online.put(name, who);
            return who;
        }
    }

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable one : open) {
            one.close();
        }
    }

    private static boolean delivered(java.util.concurrent.CompletableFuture<Boolean> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("a message to somebody on another server is delivered there, and both sides can reply")
    void deliveredAndRepliable() throws Exception {
        Server smp = new Server("smp", true);
        Server arena = new Server("arena", true);
        Player tom = smp.join("Tom");
        Player alex = arena.join("Alex");
        smp.elsewhere.put("Alex", "arena");
        arena.elsewhere.put("Tom", "smp");

        assertThat(delivered(smp.messaging.sendAcross(tom, "alex", "hi from the smp"))).isTrue();

        verify(arena.chat).tell(eq(alex), eq("essentials.msg.received"), any(TagResolver.class), any(TagResolver.class));
        verify(smp.chat).tell(eq(tom), eq("essentials.msg.sent"), any(TagResolver.class), any(TagResolver.class));
        assertThat(smp.messaging.replyTarget(tom.getUniqueId())).contains(alex.getUniqueId());
        assertThat(arena.messaging.replyTarget(alex.getUniqueId())).contains(tom.getUniqueId());

        String replyTo = arena.messaging.acrossName(tom.getUniqueId()).orElseThrow();
        assertThat(delivered(arena.messaging.sendAcross(alex, replyTo, "hi back"))).isTrue();
        verify(smp.chat).tell(eq(tom), eq("essentials.msg.received"), any(TagResolver.class), any(TagResolver.class));
    }

    @Test
    @DisplayName("somebody who ignores the sender is 'offline' to them, as on one server")
    void ignoredIsOffline() throws Exception {
        Server smp = new Server("smp", true);
        Server arena = new Server("arena", true);
        Player tom = smp.join("Tom");
        Player alex = arena.join("Alex");
        smp.elsewhere.put("Alex", "arena");
        arena.store.ignore(alex.getUniqueId(), tom.getUniqueId());

        assertThat(delivered(smp.messaging.sendAcross(tom, "Alex", "hi"))).isFalse();

        verify(smp.messages).send(tom, "essentials.player.offline", "player", "Alex");
        verify(arena.chat, never()).tell(eq(alex), any(), any(TagResolver.class), any(TagResolver.class));
    }

    @Test
    @DisplayName("somebody vanished from the sender is 'offline' to them too")
    void vanishedIsOffline() throws Exception {
        Server smp = new Server("smp", true);
        Server arena = new Server("arena", true);
        Player tom = smp.join("Tom");
        Player alex = arena.join("Alex");
        smp.elsewhere.put("Alex", "arena");
        arena.vanish.vanish(alex.getUniqueId());

        assertThat(delivered(smp.messaging.sendAcross(tom, "Alex", "hi"))).isFalse();

        verify(smp.messages).send(tom, "essentials.player.offline", "player", "Alex");
    }

    @Test
    @DisplayName("somebody who is on no server, or whose server does not answer, is offline — nobody hangs")
    void nobodyThereIsOffline() throws Exception {
        Server smp = new Server("smp", true);
        Player tom = smp.join("Tom");

        assertThat(delivered(smp.messaging.sendAcross(tom, "Ghost", "hi"))).isFalse();

        smp.elsewhere.put("Silent", "dead-server");
        assertThat(delivered(smp.messaging.sendAcross(tom, "Silent", "hi"))).isFalse();
        verify(smp.messages).send(tom, "essentials.player.offline", "player", "Ghost");
        verify(smp.messages).send(tom, "essentials.player.offline", "player", "Silent");
    }

    @Test
    @DisplayName("the names to complete and to find are the network's, not the link's")
    void namesElsewhere() {
        Server smp = new Server("smp", true);
        smp.elsewhere.put("Alex", "arena");

        assertThat(smp.messaging.playersElsewhere(() -> Set.of("FromTheLink"))).containsExactly("Alex");
        assertThat(smp.messaging.findElsewhere("alex", () -> Set.of("FromTheLink"))).contains("Alex");
    }

    @Test
    @DisplayName("off a network the link's names are used, and with no link there is nobody over there")
    void notOnANetwork() throws Exception {
        Server alone = new Server("smp", false);
        Player tom = alone.join("Tom");

        assertThat(alone.messaging.playersElsewhere(() -> Set.of("FromTheLink"))).containsExactly("FromTheLink");
        assertThat(alone.messaging.findElsewhere("fromthelink", () -> Set.of("FromTheLink"))).contains("FromTheLink");
        assertThat(delivered(alone.messaging.sendAcross(tom, "FromTheLink", "hi"))).isFalse();
        verify(alone.messages).send(tom, "essentials.player.offline", "player", "FromTheLink");
    }
}
