package de.raindancer.modules.oneblock.service;

import de.raindancer.core.platform.link.ServerLink;
import de.raindancer.core.platform.network.NetworkCalls;
import de.raindancer.modules.oneblock.model.InviteKind;
import de.raindancer.modules.oneblock.model.Reply;
import de.raindancer.modules.oneblock.model.Requester;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Elsewhere on the network: asks the island server — over its link, or on a network over RainsCore's calls to the
 * server named for it — and says "asleep" when it cannot. Any number of servers can ask the one island server.
 */
public final class RemoteGateway implements IslandsGateway {

    /** Longer than the island server's own wait for finding the invited player, so its answer arrives first. */
    static final Duration WAIT = Duration.ofSeconds(8);
    private static final Reply ASLEEP = Reply.refused("oneblock.refuse.asleep");

    /** How a question reaches the island server; it fails when nobody answers. */
    @FunctionalInterface
    public interface Asker {
        CompletableFuture<Map<String, String>> ask(String topic, Map<String, String> data, Duration wait);
    }

    private final Asker island;
    private final Duration wait;

    public RemoteGateway(Supplier<Optional<ServerLink>> link) {
        this(overLink(link), WAIT);
    }

    public RemoteGateway(Asker island, Duration wait) {
        this.island = island;
        this.wait = wait;
    }

    /** On a network: the island server is the network id the supplier gives, the proxy's name for it. */
    public static RemoteGateway overNetwork(NetworkCalls calls, Supplier<String> islandServer, Duration wait) {
        return new RemoteGateway(asker(calls, islandServer), wait);
    }

    public static RemoteGateway overNetwork(NetworkCalls calls, Supplier<String> islandServer) {
        return overNetwork(calls, islandServer, WAIT);
    }

    static Asker overLink(Supplier<Optional<ServerLink>> island) {
        return (topic, data, wait) -> {
            Optional<ServerLink> link = island.get();
            if (link.isEmpty() || !link.get().isConnected()) {
                return CompletableFuture.failedFuture(new IllegalStateException("no link to the island server"));
            }
            return link.get().request(topic, data, wait);
        };
    }

    static Asker asker(NetworkCalls calls, Supplier<String> islandServer) {
        return (topic, data, wait) -> {
            String server = islandServer.get();
            if (server == null || server.isBlank()) {
                return CompletableFuture.failedFuture(new IllegalStateException("no island server named"));
            }
            return calls.ask(server, topic, data, wait).thenApply(answer ->
                    answer.orElseThrow(() -> new IllegalStateException(server + " did not answer")));
        };
    }

    @Override
    public CompletableFuture<Reply> invite(Requester from, String to, InviteKind kind) {
        if (to == null || to.isBlank()) {
            return CompletableFuture.completedFuture(Reply.refused("oneblock.usage"));
        }
        return ask(IslandProtocol.INVITE, Map.of("from", from.id().toString(), "fromName", from.name(), "to", to,
                "kind", kind.wire()));
    }

    @Override
    public CompletableFuture<Reply> answer(Requester who, String from, boolean accept) {
        return ask(IslandProtocol.ANSWER, Map.of("player", who.id().toString(), "name", who.name(),
                "from", from == null ? "" : from, "accept", String.valueOf(accept)));
    }

    /** Nothing to ask: going there is the proxy's job, and the island server's once they arrive. */
    @Override
    public CompletableFuture<Reply> home(Requester who) {
        return CompletableFuture.completedFuture(Reply.ok("oneblock.going").andTransfer());
    }

    /** The island server keeps it for their arrival; they go over even when it could not be asked. */
    @Override
    public CompletableFuture<Optional<Reply>> then(Requester who, String arguments) {
        Reply going = Reply.ok("oneblock.going").andTransfer();
        return island.ask(IslandProtocol.THEN, Map.of("player", who.id().toString(), "arguments", arguments), wait)
                .handle((answer, failed) -> Optional.of(going));
    }

    private CompletableFuture<Reply> ask(String topic, Map<String, String> data) {
        return island.ask(topic, data, wait).handle((answer, failed) ->
                failed != null ? ASLEEP : Reply.fromWire(answer, ASLEEP));
    }
}
