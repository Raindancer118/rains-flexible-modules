package de.raindancer.modules.arena.api.wire;

import de.raindancer.core.platform.network.NetworkBus;
import de.raindancer.core.platform.network.NetworkCalls;
import de.raindancer.core.platform.network.NetworkPlayers;
import de.raindancer.core.platform.network.NetworkTravel;
import de.raindancer.core.platform.network.Presence;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * The network's way: questions go over RainsCore's {@link NetworkCalls} to a server named by its id, who is where
 * comes from presence, and somebody is moved by the network's travel.
 *
 * <p>NetworkCalls answers inside the call, on the bus's one thread — and an answer that itself waits on another
 * server (a stake taken from the bank) would wait for a reply that cannot be read until it is done. So a
 * {@link LaterHandler} answers "later" at once and delivers its real answer as a call of its own, which the asker
 * matches to its question by a request id. Every question carries who asked in {@code _from}.
 */
public final class NetworkWire implements Wire {

    /** Who is online where, as far as the arena protocol asks it. */
    public interface Directory {
        /** Ids of the servers online, this one included. */
        Set<String> servers();

        /** Somebody online on a server other than this one, not vanished. */
        Optional<Found> named(String name);

        /** Names online on other servers, not vanished. */
        Set<String> names();
    }

    /** How a player is moved to another server. */
    @FunctionalInterface
    public interface Mover {
        void send(Player player, String server);
    }

    private static final String FROM = "_from";
    private static final String REQUEST = "_request";
    private static final String LATER = "_later";
    private static final String RESULT = "arena.wire.result";
    private static final int DELIVERY_TRIES = 3;

    private final NetworkCalls calls;
    private final String self;
    private final Directory directory;
    private final Mover mover;
    private final Map<String, CompletableFuture<Map<String, String>>> waiting = new ConcurrentHashMap<>();
    private final List<NetworkBus.Subscription> answering = new ArrayList<>();
    private final ExecutorService workers = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "arena-wire");
        thread.setDaemon(true);
        return thread;
    });

    public NetworkWire(NetworkCalls calls, String self, Directory directory, Mover mover) {
        this.calls = calls;
        this.self = self;
        this.directory = directory;
        this.mover = mover;
        answering.add(calls.answer(RESULT, data -> {
            String request = data.remove(REQUEST);
            data.remove(FROM);
            CompletableFuture<Map<String, String>> asked = request == null ? null : waiting.get(request);
            if (asked != null) {
                asked.complete(new LinkedHashMap<>(data));
            }
            return Map.of();
        }));
    }

    /** On Core's presence and travel. */
    public static NetworkWire over(NetworkCalls calls, NetworkPlayers players, NetworkTravel travel) {
        String self = players.self();
        Directory directory = new Directory() {
            @Override
            public Set<String> servers() {
                return players.servers().stream().map(server -> server.id()).collect(Collectors.toSet());
            }

            @Override
            public Optional<Found> named(String name) {
                return players.named(name, false).filter(here -> !here.server().equals(self)).map(NetworkWire::found);
            }

            @Override
            public Set<String> names() {
                return players.everyone(false).stream().filter(here -> !here.server().equals(self))
                        .map(Presence.Here::name).collect(Collectors.toSet());
            }
        };
        return new NetworkWire(calls, self, directory,
                (player, server) -> travel.send(player, server, Optional.empty()));
    }

    private static Found found(Presence.Here here) {
        return new Found(here.player(), here.name(), here.server());
    }

    @Override
    public String self() {
        return self;
    }

    @Override
    public Optional<String> defaultPeer() {
        return Optional.empty();
    }

    @Override
    public boolean reachable() {
        return directory.servers().stream().anyMatch(id -> !id.equals(self));
    }

    @Override
    public boolean reachable(String peer) {
        return peer != null && !peer.equals(self) && directory.servers().contains(peer);
    }

    @Override
    public CompletableFuture<Map<String, String>> ask(String peer, String topic, Map<String, String> data,
                                                      Duration timeout) {
        CompletableFuture<Map<String, String>> result = new CompletableFuture<>();
        if (peer == null || peer.isBlank() || peer.equals(self)) {
            result.completeExceptionally(new IllegalStateException("no server to ask for " + topic));
            return result;
        }
        String request = UUID.randomUUID().toString();
        waiting.put(request, result);
        result.whenComplete((answer, failure) -> waiting.remove(request));
        result.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
        Map<String, String> question = new LinkedHashMap<>(data);
        question.put(FROM, self);
        question.put(REQUEST, request);
        calls.ask(peer, topic, question, timeout).whenComplete((answer, failure) -> {
            if (failure != null || answer.isEmpty()) {
                result.completeExceptionally(new IllegalStateException(peer + " did not answer " + topic));
                return;
            }
            Map<String, String> plain = new LinkedHashMap<>(answer.get());
            if (plain.remove(LATER) == null) {
                result.complete(plain);
            }
        });
        return result;
    }

    @Override
    public void tell(String peer, String topic, Map<String, String> data) {
        if (peer == null || peer.isBlank() || peer.equals(self)) {
            return;
        }
        Map<String, String> question = new LinkedHashMap<>(data);
        question.put(FROM, self);
        calls.ask(peer, topic, question, ASK);
    }

    @Override
    public synchronized void on(String topic, Handler handler) {
        answering.add(calls.answer(topic, data -> {
            String from = data.remove(FROM);
            data.remove(REQUEST);
            Map<String, String> answer = handler.handle(from == null ? "" : from, new LinkedHashMap<>(data));
            return answer == null ? Map.of() : answer;
        }));
    }

    @Override
    public synchronized void onLater(String topic, LaterHandler handler) {
        answering.add(calls.answer(topic, data -> {
            String from = data.remove(FROM);
            String request = data.remove(REQUEST);
            if (from == null || request == null) {
                throw new IllegalArgumentException("a question that says neither who nor which");
            }
            Map<String, String> question = new LinkedHashMap<>(data);
            CompletableFuture.supplyAsync(() -> handler.handle(from, question), workers)
                    .thenCompose(answer -> answer)
                    .handle((answer, failure) -> {
                        deliver(from, request, failure != null ? Map.of("ok", "false") : answer == null ? Map.of() : answer,
                                DELIVERY_TRIES);
                        return null;
                    });
            return Map.of(LATER, "1");
        }));
    }

    private void deliver(String to, String request, Map<String, String> answer, int triesLeft) {
        Map<String, String> reply = new LinkedHashMap<>(answer);
        reply.put(REQUEST, request);
        reply.put(FROM, self);
        calls.ask(to, RESULT, reply, ASK).thenAccept(delivered -> {
            if (delivered.isEmpty() && triesLeft > 1) {
                deliver(to, request, answer, triesLeft - 1);
            }
        });
    }

    @Override
    public CompletableFuture<Optional<Found>> find(String name) {
        return CompletableFuture.completedFuture(name == null || name.isBlank() ? Optional.empty()
                : directory.named(name));
    }

    @Override
    public Set<String> names() {
        return directory.names();
    }

    @Override
    public void send(Player player, String server) {
        mover.send(player, server);
    }

    @Override
    public synchronized void close() {
        answering.forEach(NetworkBus.Subscription::close);
        answering.clear();
        waiting.values().forEach(asked -> asked.completeExceptionally(new IllegalStateException("closed")));
        workers.shutdownNow();
    }
}
