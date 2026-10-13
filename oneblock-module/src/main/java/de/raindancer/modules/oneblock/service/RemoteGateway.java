package de.raindancer.modules.oneblock.service;

import de.raindancer.core.platform.link.ServerLink;
import de.raindancer.modules.oneblock.model.InviteKind;
import de.raindancer.modules.oneblock.model.Reply;
import de.raindancer.modules.oneblock.model.Requester;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Elsewhere on the network: asks the island server over its link, and says "asleep" when it cannot. */
public final class RemoteGateway implements IslandsGateway {

    /** Longer than the island server's own wait for finding the invited player, so its answer arrives first. */
    static final Duration WAIT = Duration.ofSeconds(8);
    private static final Reply ASLEEP = Reply.refused("oneblock.refuse.asleep");

    private final Supplier<Optional<ServerLink>> island;

    public RemoteGateway(Supplier<Optional<ServerLink>> island) {
        this.island = island;
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

    /** Nothing to ask: going there is the proxy's job, and BentoBox's once they arrive. */
    @Override
    public CompletableFuture<Reply> home(Requester who) {
        return CompletableFuture.completedFuture(Reply.ok("oneblock.going").andTransfer());
    }

    private CompletableFuture<Reply> ask(String topic, Map<String, String> data) {
        Optional<ServerLink> link = island.get();
        if (link.isEmpty() || !link.get().isConnected()) {
            return CompletableFuture.completedFuture(ASLEEP);
        }
        return link.get().request(topic, data, WAIT).handle((answer, failed) ->
                failed != null ? ASLEEP : Reply.fromWire(answer, ASLEEP));
    }
}
