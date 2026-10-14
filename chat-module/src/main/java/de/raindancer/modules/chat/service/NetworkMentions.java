package de.raindancer.modules.chat.service;

import de.raindancer.core.platform.network.NetworkBus;
import de.raindancer.core.platform.network.NetworkCalls;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A mention of somebody who is on another server: the sender's server asks the server they are on to ping them,
 * and hears whether it did. Only a player who is on the asking server can mention anybody through it. A "no" — they left in the meantime — is how the caller knows to leave a note instead.
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
    private final BiPredicate<UUID, String> playerOn;
    private final Rejected rejected = new Rejected();
    private final Here here;
    private volatile NetworkBus.Subscription answering;

    /**
     * @param serverOf the other server a player is visibly on, empty when they are offline or hidden everywhere
     * @param playerOn whether a player is, by the network's presence, on the server with that id
     */
    public NetworkMentions(Supplier<Optional<NetworkCalls>> calls, Function<UUID, Optional<String>> serverOf,
                           BiPredicate<UUID, String> playerOn, Here here) {
        this.calls = calls;
        this.serverOf = serverOf;
        this.playerOn = playerOn;
        this.here = here;
    }

    public void listen() {
        calls.get().ifPresent(live -> answering = live.answerFrom(TOPIC, (server, question) ->
                CompletableFuture.completedFuture(pinged(server, question))));
    }

    private Map<String, String> pinged(String server, Map<String, String> question) {
        try {
            if (!playerOn.test(UUID.fromString(question.get("fromId")), server)) {
                rejected.of(server, TOPIC);
                return Map.of("pinged", "false");
            }
            return Map.of("pinged", String.valueOf(here.ping(UUID.fromString(question.get("player")),
                    question.get("from"), question.get("text"))));
        } catch (RuntimeException garbled) {
            return Map.of("pinged", "false");
        }
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
    public CompletableFuture<Boolean> ping(UUID player, UUID mentioner, String from, String text) {
        Optional<String> server = serverOf.apply(player);
        Optional<NetworkCalls> live = calls.get();
        if (server.isEmpty() || live.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        return live.get().ask(server.get(), TOPIC, Map.of("player", player.toString(),
                        "fromId", mentioner.toString(), "from", from == null ? "" : from, "text", text == null ? "" : text), TIMEOUT)
                .thenApply(answer -> answer.map(reply -> "true".equals(reply.get("pinged"))).orElse(false))
                .exceptionally(failed -> false);
    }
}
