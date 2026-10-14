package de.raindancer.modules.chat.service;

import de.raindancer.core.platform.network.LocalBus;
import de.raindancer.core.platform.network.NetworkCalls;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** /announce, /ad, /chat clear, freeze and slowmode on every server of a network. */
class ChatNetworkTest {

    private final LocalBus.Hub hub = new LocalBus.Hub();
    private final AtomicLong clock = new AtomicLong(1000);

    /** One server: what it did, in order, and its own freeze and slowmode. */
    private final class Server implements ChatNetwork.Receiver {
        final List<String> done = new ArrayList<>();
        final Set<UUID> muted = new HashSet<>();
        ChatNetwork.State state = new ChatNetwork.State(false, -1);
        final ChatNetwork network;

        Server(String id, boolean onNetwork, List<String> others) {
            var bus = hub.bus(id);
            NetworkCalls calls = new NetworkCalls(bus);
            network = new ChatNetwork(bus, () -> Optional.of(calls), () -> onNetwork, () -> others, muted::contains, this, clock::get);
        }

        @Override
        public void banner(String text) {
            done.add("banner " + text);
        }

        @Override
        public void ad(UUID sender, String name, String text) {
            done.add("ad " + name + " " + text);
        }

        @Override
        public void clear(String by) {
            done.add("clear " + by);
        }

        @Override
        public void freeze(boolean frozen, String by) {
            state = new ChatNetwork.State(frozen, state.slowmode());
            done.add((frozen ? "freeze " : "unfreeze ") + by);
        }

        @Override
        public void slowmode(int seconds, String by) {
            state = new ChatNetwork.State(state.frozen(), seconds);
            done.add("slowmode " + seconds + " " + by);
        }

        @Override
        public ChatNetwork.State state() {
            return state;
        }

        @Override
        public void adopt(ChatNetwork.State adopted) {
            state = adopted;
            done.add("adopted " + adopted);
        }
    }

    private Server server(String id, String... others) {
        Server server = new Server(id, true, List.of(others));
        server.network.listen();
        return server;
    }

    @Test
    @DisplayName("an announcement is shown here and on every other server")
    void announceEverywhere() {
        Server smp = server("smp");
        Server arena = server("arena");
        Server lobby = server("lobby");

        smp.network.announce("Restart in 5");
        hub.drain();

        assertThat(smp.done).containsExactly("banner Restart in 5");
        assertThat(arena.done).containsExactly("banner Restart in 5");
        assertThat(lobby.done).containsExactly("banner Restart in 5");
    }

    @Test
    @DisplayName("an ad is shown everywhere except where its sender is muted")
    void adEverywhere() {
        Server smp = server("smp");
        Server arena = server("arena");
        Server lobby = server("lobby");
        UUID lilly = UUID.randomUUID();
        arena.muted.add(lilly);

        smp.network.ad(lilly, "Lilly", "Selling cows");
        hub.drain();

        assertThat(smp.done).containsExactly("ad Lilly Selling cows");
        assertThat(arena.done).isEmpty();
        assertThat(lobby.done).containsExactly("ad Lilly Selling cows");
    }

    @Test
    @DisplayName("clear, freeze and slowmode reach every server, with who did it")
    void controlsEverywhere() {
        Server smp = server("smp");
        Server arena = server("arena");

        smp.network.clear("Tom");
        smp.network.freeze(true, "Tom");
        smp.network.slowmode(10, "Tom");
        smp.network.slowmode(-1, "Tom");
        smp.network.freeze(false, "Tom");
        hub.drain();

        List<String> expected = List.of("clear Tom", "freeze Tom", "slowmode 10 Tom", "slowmode -1 Tom", "unfreeze Tom");
        assertThat(smp.done).isEqualTo(expected);
        assertThat(arena.done).isEqualTo(expected);
    }

    @Test
    @DisplayName("a slowmode out of range, or garbage on the topic, changes nothing")
    void garbageIgnored() {
        Server arena = server("arena");
        var rogue = hub.bus("rogue");

        rogue.publish(ChatNetwork.CONTROL, "slowmode", "{\"by\":\"x\",\"value\":9999}");
        rogue.publish(ChatNetwork.CONTROL, "freeze", "{broken");
        rogue.publish(ChatNetwork.CONTROL, "unknown", "{\"by\":\"x\"}");
        rogue.publish(ChatNetwork.ANNOUNCE, "ad:not-a-uuid", "{\"text\":\"x\",\"name\":\"y\"}");
        rogue.publish(ChatNetwork.ANNOUNCE, "banner", "");
        hub.drain();

        assertThat(arena.done).isEmpty();
        assertThat(arena.state).isEqualTo(new ChatNetwork.State(false, -1));
    }

    @Test
    @DisplayName("a server that starts later takes the current freeze and slowmode from the others")
    void lateJoinerCatchesUp() throws Exception {
        Server smp = server("smp");
        hub.drain();
        clock.set(5000);
        smp.network.freeze(true, "Tom");
        smp.network.slowmode(15, "Tom");
        hub.drain();

        Server late = new Server("late", true, List.of("smp"));
        late.network.listen();
        late.network.catchUp().get();

        assertThat(late.state).isEqualTo(new ChatNetwork.State(true, 15));
        assertThat(late.done).containsExactly("adopted State[frozen=true, slowmode=15]");
    }

    @Test
    @DisplayName("a server that already knows something newer does not take an older state")
    void newerWins() throws Exception {
        Server smp = server("smp");
        clock.set(2000);
        smp.network.freeze(true, "Tom");
        hub.drain();

        Server late = new Server("late", true, List.of("smp"));
        late.network.listen();
        clock.set(9000);
        late.network.freeze(false, "Lilly");
        late.network.catchUp().get();

        assertThat(late.state.frozen()).isFalse();
        assertThat(late.done).doesNotContain("adopted State[frozen=true, slowmode=-1]");
    }

    @Test
    @DisplayName("a server nobody has changed anything on has nothing to hand over")
    void nothingToAdopt() throws Exception {
        server("smp");
        Server late = new Server("late", true, List.of("smp"));
        late.network.listen();

        late.network.catchUp().get();

        assertThat(late.done).isEmpty();
    }

    @Test
    @DisplayName("off a network it only happens here and nothing is published")
    void aloneIsLocal() {
        Server alone = new Server("smp", false, List.of());
        alone.network.listen();
        Server arena = server("arena");

        alone.network.announce("hi");
        alone.network.freeze(true, "Tom");
        hub.drain();

        assertThat(alone.done).containsExactly("banner hi", "freeze Tom");
        assertThat(arena.done).isEmpty();
    }

    @Test
    @DisplayName("stopping lets go of the topics")
    void stopReleases() {
        Server smp = server("smp");
        Server arena = server("arena");
        arena.network.stop();

        smp.network.announce("hi");
        hub.drain();

        assertThat(arena.done).isEmpty();
    }
}
