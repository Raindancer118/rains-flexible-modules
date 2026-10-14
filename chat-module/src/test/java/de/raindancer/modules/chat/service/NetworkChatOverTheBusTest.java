package de.raindancer.modules.chat.service;

import de.raindancer.core.platform.network.LocalBus;
import de.raindancer.modules.chat.NetworkChatSettings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** On a network public chat travels over the bus, so every server hears it — not just one linked one. */
class NetworkChatOverTheBusTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final LocalBus.Hub hub = new LocalBus.Hub();

    /** One server of the network with what it showed its players. */
    private final class Server {
        final List<Component> shown = new ArrayList<>();
        final Set<UUID> muted = new HashSet<>();
        final NetworkChatService chat;

        Server(String id, NetworkChatSettings settings) {
            chat = new NetworkChatService(Optional::empty, hub.bus(id), () -> true, muted::contains, shown::add,
                    (tag, line) -> Component.text("[" + tag + "] ").append(line), settings);
            chat.listen();
        }

        List<String> heard() {
            return shown.stream().map(PLAIN::serialize).toList();
        }
    }

    private Server server(String id, String tag) {
        return new Server(id, new NetworkChatSettings(true, tag));
    }

    @Test
    @DisplayName("a line said on one server is shown on every other server, behind the saying server's tag")
    void everyServerHears() {
        Server smp = server("smp", "SMP");
        Server arena = server("arena", "Arena");
        Server hub3 = server("lobby", "Lobby");

        smp.chat.said(UUID.randomUUID(), "Lilly", "gg", Component.text("Lilly: gg"));
        hub.drain();

        assertThat(arena.heard()).containsExactly("[SMP] Lilly: gg");
        assertThat(hub3.heard()).containsExactly("[SMP] Lilly: gg");
        assertThat(smp.heard()).isEmpty();
    }

    @Test
    @DisplayName("somebody muted on a server is not heard there, whichever server they spoke on")
    void muteHoldsOnEachServer() {
        Server smp = server("smp", "SMP");
        Server arena = server("arena", "Arena");
        Server lobby = server("lobby", "Lobby");
        UUID lilly = UUID.randomUUID();
        arena.muted.add(lilly);

        smp.chat.said(lilly, "Lilly", "gg", Component.text("Lilly: gg"));
        hub.drain();

        assertThat(arena.heard()).isEmpty();
        assertThat(lobby.heard()).containsExactly("[SMP] Lilly: gg");
    }

    @Test
    @DisplayName("with no tag set a network server is known by its id")
    void tagDefaultsToTheServerId() {
        Server smp = server("smp", "");
        Server arena = server("arena", "Arena");

        smp.chat.said(UUID.randomUUID(), "Lilly", "gg", Component.text("Lilly: gg"));
        hub.drain();

        assertThat(arena.heard()).containsExactly("[smp] Lilly: gg");
    }

    @Test
    @DisplayName("a server with sharing switched off neither sends nor shows")
    void offMeansOff() {
        Server smp = new Server("smp", new NetworkChatSettings(false, "SMP"));
        Server arena = server("arena", "Arena");

        smp.chat.said(UUID.randomUUID(), "Lilly", "gg", Component.text("Lilly: gg"));
        arena.chat.said(UUID.randomUUID(), "Max", "hi", Component.text("Max: hi"));
        hub.drain();

        assertThat(arena.heard()).isEmpty();
        assertThat(smp.heard()).isEmpty();
    }

    @Test
    @DisplayName("something that is not a chat line is dropped rather than shown half-read")
    void garbageDropped() {
        Server arena = server("arena", "Arena");
        var rogue = hub.bus("rogue");

        rogue.publish(NetworkChatService.TOPIC, "not-a-uuid", "{broken");
        rogue.publish(NetworkChatService.TOPIC, UUID.randomUUID().toString(), "");
        hub.drain();

        assertThat(arena.heard()).isEmpty();
    }

    @Test
    @DisplayName("stopping lets go of the topic")
    void stopReleases() {
        Server smp = server("smp", "SMP");
        Server arena = server("arena", "Arena");
        arena.chat.stop();

        smp.chat.said(UUID.randomUUID(), "Lilly", "gg", Component.text("Lilly: gg"));
        hub.drain();

        assertThat(arena.heard()).isEmpty();
    }

    @Test
    @DisplayName("off a network the bus is not used: the line goes over the link as before")
    void notOnANetworkTheBusStaysSilent() {
        var smpBus = hub.bus("smp");
        List<Component> arenaHeard = new ArrayList<>();
        NetworkChatService arena = new NetworkChatService(Optional::empty, hub.bus("arena"), () -> true,
                id -> false, arenaHeard::add, (tag, line) -> line, new NetworkChatSettings(true, "Arena"));
        arena.listen();
        NetworkChatService alone = new NetworkChatService(Optional::empty, smpBus, () -> false,
                id -> false, c -> { }, (tag, line) -> line, new NetworkChatSettings(true, "SMP"));
        alone.listen();

        alone.said(UUID.randomUUID(), "Lilly", "gg", Component.text("Lilly: gg"));
        hub.drain();

        assertThat(arenaHeard).isEmpty();
    }
}
