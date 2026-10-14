package de.raindancer.modules.arena.api.wire;

import de.raindancer.core.platform.network.LocalBus;
import de.raindancer.core.platform.network.NetworkCalls;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Two survival servers and an arena in one JVM: what the arena protocol needs of a network, and nothing of a link. */
class NetworkWireTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private final LocalBus.Hub hub = new LocalBus.Hub();
    private final List<AutoCloseable> open = new ArrayList<>();
    private final List<String> sent = new CopyOnWriteArrayList<>();

    private NetworkWire wire(String id, Set<String> online, java.util.function.Function<String, Optional<Wire.Found>> who) {
        NetworkCalls calls = new NetworkCalls(hub.bus(id));
        NetworkWire.Directory directory = new NetworkWire.Directory() {
            @Override
            public Set<String> servers() {
                return online;
            }

            @Override
            public Optional<Wire.Found> named(String name) {
                return who.apply(name);
            }

            @Override
            public Set<String> names() {
                return Set.of("Bea");
            }
        };
        NetworkWire wire = new NetworkWire(calls, id, directory, (player, server) -> sent.add(id + ">" + server));
        open.add(wire::close);
        open.add(calls);
        return wire;
    }

    private NetworkWire arena() {
        return wire("arena", Set.of("smp-1", "smp-2", "arena"), name -> Optional.empty());
    }

    private NetworkWire smp(String id) {
        return wire(id, Set.of("smp-1", "smp-2", "arena"), name -> Optional.empty());
    }

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable thing : open) {
            thing.close();
        }
    }

    private static Map<String, String> get(CompletableFuture<Map<String, String>> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("a question reaches the server asked, says who asked, and the answer comes back")
    void askAndAnswer() throws Exception {
        NetworkWire arena = arena();
        NetworkWire first = smp("smp-1");
        arena.on("arena.intent", (peer, data) -> Map.of("ok", "true", "heardFrom", peer, "player", data.get("player")));

        Map<String, String> answer = get(first.ask("arena", "arena.intent", Map.of("player", "Alex")));

        assertThat(answer).containsEntry("heardFrom", "smp-1").containsEntry("player", "Alex").containsEntry("ok", "true");
        assertThat(answer.keySet()).noneMatch(key -> key.startsWith("_"));
    }

    @Test
    @DisplayName("several survival servers ask one arena, and each answer goes back to the one who asked")
    void manyServersOneArena() throws Exception {
        NetworkWire arena = arena();
        NetworkWire first = smp("smp-1");
        NetworkWire second = smp("smp-2");
        arena.on("arena.invite", (peer, data) -> Map.of("ok", "true", "for", peer));

        assertThat(get(first.ask("arena", "arena.invite", Map.of()))).containsEntry("for", "smp-1");
        assertThat(get(second.ask("arena", "arena.invite", Map.of()))).containsEntry("for", "smp-2");
    }

    @Test
    @DisplayName("the arena asks one particular survival server; the others never hear it")
    void askOneOfMany() throws Exception {
        NetworkWire arena = arena();
        NetworkWire first = smp("smp-1");
        NetworkWire second = smp("smp-2");
        AtomicInteger heardByFirst = new AtomicInteger();
        first.on("stake.refund", (peer, data) -> {
            heardByFirst.incrementAndGet();
            return Map.of("ok", "true");
        });
        second.on("stake.refund", (peer, data) -> Map.of("ok", "true", "who", "second"));

        assertThat(get(arena.ask("smp-2", "stake.refund", Map.of()))).containsEntry("who", "second");
        assertThat(heardByFirst).hasValue(0);
    }

    @Test
    @DisplayName("nobody answering is a failed future after the timeout, not a hang")
    void nobodyAnswers() {
        NetworkWire arena = arena();
        smp("smp-1");

        CompletableFuture<Map<String, String>> asked = arena.ask("smp-1", "nothing.listens", Map.of(), Duration.ofMillis(300));

        assertThatThrownBy(() -> asked.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
    }

    @Test
    @DisplayName("a handler that throws fails the question at once")
    void throwingHandler() {
        NetworkWire arena = arena();
        smp("smp-1").on("boom", (peer, data) -> {
            throw new IllegalStateException("broken");
        });

        assertThatThrownBy(() -> arena.ask("smp-1", "boom", Map.of(), Duration.ofSeconds(5)).get(4, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);
    }

    @Test
    @DisplayName("a server that is not a peer, and one that is this server, are not asked")
    void notAPeer() {
        NetworkWire arena = arena();

        assertThatThrownBy(() -> arena.ask("", "x", Map.of()).get(1, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
        assertThatThrownBy(() -> arena.ask("arena", "x", Map.of()).get(1, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
    }

    @Test
    @DisplayName("a later answer may wait on a third server without holding up its own replies")
    void laterWaitsOnAThirdServer() throws Exception {
        NetworkWire arena = arena();
        NetworkWire smp = smp("smp-1");
        NetworkWire bank = smp("smp-2");
        bank.on("economy.move", (peer, data) -> Map.of("outcome", "DONE"));
        // What escrow does: takes money from the bank, which answers on the same bus this handler was called from.
        smp.onLater("stake.hold", (peer, data) -> CompletableFuture.supplyAsync(() -> {
            Map<String, String> moved = smp.ask("smp-2", "economy.move", Map.of()).join();
            return Map.of("ok", String.valueOf("DONE".equals(moved.get("outcome"))));
        }));

        assertThat(get(arena.ask("smp-1", "stake.hold", Map.of("fight", "f1")))).containsEntry("ok", "true");
    }

    @Test
    @DisplayName("a later answer that fails is a refusal, not silence")
    void laterFails() throws Exception {
        NetworkWire arena = arena();
        smp("smp-1").onLater("stake.hold", (peer, data) -> CompletableFuture.failedFuture(new IllegalStateException("no bank")));

        assertThat(get(arena.ask("smp-1", "stake.hold", Map.of()))).containsEntry("ok", "false");
    }

    @Test
    @DisplayName("a later answer that never comes fails the question when its time is up")
    void laterNeverComes() {
        NetworkWire arena = arena();
        smp("smp-1").onLater("stake.hold", (peer, data) -> new CompletableFuture<>());

        CompletableFuture<Map<String, String>> asked = arena.ask("smp-1", "stake.hold", Map.of(), Duration.ofMillis(400));

        assertThatThrownBy(() -> asked.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
    }

    @Test
    @DisplayName("a later answer sees who asked and what, like any other")
    void laterSeesWhoAsked() throws Exception {
        NetworkWire arena = arena();
        smp("smp-2").onLater("gear.copy", (peer, data) ->
                CompletableFuture.completedFuture(Map.of("ok", "true", "to", peer, "player", data.get("player"))));

        assertThat(get(arena.ask("smp-2", "gear.copy", Map.of("player", ALEX.toString()))))
                .containsEntry("to", "arena").containsEntry("player", ALEX.toString());
    }

    @Test
    @DisplayName("tell reaches the handler and nobody waits for an answer")
    void tell() throws Exception {
        NetworkWire arena = arena();
        CompletableFuture<String> heard = new CompletableFuture<>();
        smp("smp-1").on("arena.returning", (peer, data) -> {
            heard.complete(peer + ":" + data.get("player"));
            return null;
        });

        arena.tell("smp-1", "arena.returning", Map.of("player", "Alex"));

        assertThat(heard.get(10, TimeUnit.SECONDS)).isEqualTo("arena:Alex");
    }

    @Test
    @DisplayName("a peer is reachable while the network lists it online; nobody is reachable alone")
    void reachable() {
        NetworkWire arena = arena();
        NetworkWire alone = wire("arena", Set.of("arena"), name -> Optional.empty());

        assertThat(arena.reachable()).isTrue();
        assertThat(arena.reachable("smp-2")).isTrue();
        assertThat(arena.reachable("smp-9")).isFalse();
        assertThat(alone.reachable()).isFalse();
        assertThat(arena.defaultPeer()).isEmpty();
    }

    @Test
    @DisplayName("a name is found through presence, with the server they are on — no question asked")
    void find() throws Exception {
        NetworkWire arena = wire("arena", Set.of("smp-1", "arena"),
                name -> name.equals("Alex") ? Optional.of(new Wire.Found(ALEX, "Alex", "smp-1")) : Optional.empty());

        assertThat(arena.find("Alex").get(1, TimeUnit.SECONDS)).contains(new Wire.Found(ALEX, "Alex", "smp-1"));
        assertThat(arena.find("Nobody").get(1, TimeUnit.SECONDS)).isEmpty();
        assertThat(arena.names()).containsExactly("Bea");
    }

    @Test
    @DisplayName("sending a player goes through the network's travel")
    void send() {
        NetworkWire arena = arena();

        arena.send(null, "smp-2");

        assertThat(sent).containsExactly("arena>smp-2");
    }
}
