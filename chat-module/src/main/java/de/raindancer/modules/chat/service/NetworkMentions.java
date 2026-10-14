package de.raindancer.modules.chat.service;

import de.raindancer.core.platform.network.NetworkBus;
import de.raindancer.core.platform.network.NetworkCalls;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A mention of somebody who is on another server: the sender's server asks the server they are on to ping them,
 * and hears whether it did. A "no" — they left in the meantime — is how the caller knows to leave a note instead.
 */
public final class NetworkMentions {

    public static final String TOPIC = "chat.mention";

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    /** Pings a player on this server; false when they are not here. */
    @FunctionalInterface
    public interface Here {
        boolean ping(UUID player, String from, String text);
    }

    private final Supplier<Optional<NetworkCalls>> calls;
    private final Function<UUID, Optional<String>> serverOf;
    private final Here here;
    private volatile NetworkBus.Subscription answering;

    /**
     * @param serverOf the other server a player is visibly on, empty when they are offline or hidden everywhere
     */
    public NetworkMentions(Supplier<Optional<NetworkCalls>> calls, Function<UUID, Optional<String>> serverOf,
                           Here here) {
        this.calls = calls;
        this.serverOf = serverOf;
        this.here = here;
    }

    public void listen() {
        calls.get().ifPresent(live -> answering = live.answer(TOPIC, question -> Map.of("pinged", String.valueOf(
                here.ping(UUID.fromString(question.get("player")), question.get("from"), question.get("text"))))));
    }

    public void stop() {
        NetworkBus.Subscription listening = answering;
        if (listening != null) {
            listening.close();
            answering = null;
        }
    }

    /** Whether the player is online and visible on another server. */
    public boolean reachable(UUID player) {
        return serverOf.apply(player).isPresent();
    }

    /** Completes with whether the player was pinged; never fails. */
    public CompletableFuture<Boolean> ping(UUID player, String from, String text) {
        Optional<String> server = serverOf.apply(player);
        Optional<NetworkCalls> live = calls.get();
        if (server.isEmpty() || live.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        return live.get().ask(server.get(), TOPIC, Map.of("player", player.toString(),
                        "from", from == null ? "" : from, "text", text == null ? "" : text), TIMEOUT)
                .thenApply(answer -> answer.map(reply -> "true".equals(reply.get("pinged"))).orElse(false))
                .exceptionally(failed -> false);
    }
}
