package de.raindancer.modules.tpa.store;

import de.raindancer.core.platform.network.NetworkBus;
import de.raindancer.core.platform.network.NetworkCalls;
import de.raindancer.core.platform.network.NetworkPlayers;
import de.raindancer.core.platform.network.Presence;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

/**
 * What teleport requests need of the network: who is where, and a question to the server somebody is on.
 *
 * <p>A request lives on the server of the person asked, which alone may accept it for them: the topics here are
 * answered with {@code answerFrom}, and the one answering checks that the server that called is the one the
 * player named in it is on.
 */
public final class TpaNetwork {

    public static final String ASK = "tpa-ask";
    public static final String ACCEPT = "tpa-accept";
    public static final String END = "tpa-end";

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** Who is where; Core's {@link NetworkPlayers} in a server, a fake in a test. */
    public interface Directory {
        Optional<Presence.Here> where(UUID player, boolean withVanished);

        Optional<Presence.Here> named(String name, boolean withVanished);

        List<Presence.Here> everyone(boolean withVanished);

        String self();

        static Directory of(NetworkPlayers players) {
            return new Directory() {
                @Override
                public Optional<Presence.Here> where(UUID player, boolean withVanished) {
                    return players.where(player, withVanished);
                }

                @Override
                public Optional<Presence.Here> named(String name, boolean withVanished) {
                    return players.named(name, withVanished);
                }

                @Override
                public List<Presence.Here> everyone(boolean withVanished) {
                    return players.everyone(withVanished);
                }

                @Override
                public String self() {
                    return players.self();
                }
            };
        }
    }

    private final Directory directory;
    private final NetworkCalls calls;

    public TpaNetwork(Directory directory, NetworkCalls calls) {
        this.directory = directory;
        this.calls = calls;
    }

    public String self() {
        return directory.self();
    }

    /** Somebody on another server, by name; never a vanished player. */
    public Optional<Presence.Here> find(String name) {
        return directory.named(name, false).filter(there -> !there.server().equals(self()));
    }

    /** Everybody visible on the other servers, for a list. */
    public List<Presence.Here> everyoneElse() {
        return directory.everyone(false).stream().filter(there -> !there.server().equals(self())).toList();
    }

    /** The other server this player is on, vanished or not — a request already made does not end with a vanish. */
    public Optional<String> serverOf(UUID player) {
        return directory.where(player, true).map(Presence.Here::server).filter(server -> !server.equals(self()));
    }

    /** Whether the player is on {@code server}: what makes a call from it about them believable. */
    public boolean hostedBy(UUID player, String server) {
        return directory.where(player, true).map(there -> there.server().equals(server)).orElse(false);
    }

    public CompletableFuture<Optional<Map<String, String>>> ask(String server, String topic, Map<String, String> data) {
        return calls.ask(server, topic, data, TIMEOUT);
    }

    public NetworkBus.Subscription answer(String topic,
                                          BiFunction<String, Map<String, String>, CompletableFuture<Map<String, String>>> handler) {
        return calls.answerFrom(topic, handler);
    }
}
