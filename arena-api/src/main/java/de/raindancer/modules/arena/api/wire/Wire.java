package de.raindancer.modules.arena.api.wire;

import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * How the arena server and the survival servers reach each other — the one seam between the game logic and the
 * transport. Over a 1.x server link ({@link LinkWire}) there is exactly one other end and every peer argument is
 * ignored; on a network ({@link NetworkWire}) a peer is a server id from {@code network.yml}, and any number of
 * survival servers can talk to the one arena.
 *
 * <p>A peer is who to ask. Whoever asked is told to a handler as its first argument, so an answer — or a later
 * question — can go back to that very server.
 */
public interface Wire {

    /** The answer a question gets within its call. */
    @FunctionalInterface
    interface Handler {
        /** @param peer who asked; {@code ""} on a link */
        Map<String, String> handle(String peer, Map<String, String> data);
    }

    /**
     * An answer that takes its time — it waits for the world, a database, the bank. The asker waits for it; nothing
     * else is held up meanwhile (on a network a handler that waits would hold up the very reply it waits for).
     */
    @FunctionalInterface
    interface LaterHandler {
        CompletableFuture<Map<String, String>> handle(String peer, Map<String, String> data);
    }

    /** Somebody online on another server. {@code server} is {@code ""} on a link. */
    record Found(UUID id, String name, String server) {
    }

    Duration ASK = Duration.ofSeconds(8);

    /** This server's id; {@code ""} on a link, which does not name its ends. */
    String self();

    /** The one peer there is when there is only one (a link); empty on a network, where the caller must choose. */
    Optional<String> defaultPeer();

    /** Whether there is anybody to talk to at all. */
    boolean reachable();

    /** Whether this server is there to talk to right now. */
    boolean reachable(String peer);

    /** Fails — never hangs — when nobody answers within {@code timeout}. */
    CompletableFuture<Map<String, String>> ask(String peer, String topic, Map<String, String> data, Duration timeout);

    default CompletableFuture<Map<String, String>> ask(String peer, String topic, Map<String, String> data) {
        return ask(peer, topic, data, ASK);
    }

    /** Says something nobody has to answer. */
    void tell(String peer, String topic, Map<String, String> data);

    void on(String topic, Handler handler);

    void onLater(String topic, LaterHandler handler);

    /** Who a name is, wherever they are online but here; a vanished player is nobody. */
    CompletableFuture<Optional<Found>> find(String name);

    /** Names online elsewhere, for completion. */
    Set<String> names();

    /** Sends a player to a server by the transport's own way: the proxy, or the network's travel. On their thread. */
    void send(Player player, String server);

    /** Lets go of every topic this wire answers. */
    void close();
}
