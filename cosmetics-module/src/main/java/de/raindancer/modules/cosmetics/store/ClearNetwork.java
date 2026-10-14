package de.raindancer.modules.cosmetics.store;

import de.raindancer.core.platform.network.NetworkBus;
import de.raindancer.core.platform.network.NetworkCalls;
import de.raindancer.core.platform.network.NetworkPlayers;
import de.raindancer.core.platform.network.Presence;
import de.raindancer.modules.cosmetics.model.ClearScope;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

/**
 * Clearing the particle of somebody who is on another server: only that server may touch their data, so it is
 * asked. The one answering checks that the player is on it.
 */
public final class ClearNetwork {

    public static final String TOPIC = "cosmetics-clear";

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** Who is where, and a word to somebody wherever they are; Core's {@link NetworkPlayers} in a server. */
    public interface Directory {
        Optional<Presence.Here> where(UUID player, boolean withVanished);

        String self();

        boolean tell(UUID player, String key, String... values);

        static Directory of(NetworkPlayers players) {
            return new Directory() {
                @Override
                public Optional<Presence.Here> where(UUID player, boolean withVanished) {
                    return players.where(player, withVanished);
                }

                @Override
                public String self() {
                    return players.self();
                }

                @Override
                public boolean tell(UUID player, String key, String... values) {
                    return players.tell(player, key, values);
                }
            };
        }
    }

    /** What the server asked did: whether the player was there, and whether they wore a particle or wings. */
    public record Result(boolean found, boolean hadParticles) {
        static Result of(Map<String, String> answer) {
            return new Result("true".equals(answer.get("found")), "true".equals(answer.get("had")));
        }

        Map<String, String> toAnswer() {
            return Map.of("found", String.valueOf(found), "had", String.valueOf(hadParticles));
        }
    }

    private final Directory directory;
    private final NetworkCalls calls;

    public ClearNetwork(Directory directory, NetworkCalls calls) {
        this.directory = directory;
        this.calls = calls;
    }

    /** The other server this player is on, vanished or not. */
    public Optional<String> serverOf(UUID player) {
        return directory.where(player, true).map(Presence.Here::server).filter(server -> !server.equals(directory.self()));
    }

    public boolean hostedHere(UUID player) {
        return directory.where(player, true).map(there -> there.server().equals(directory.self())).orElse(false);
    }

    public boolean tell(UUID player, String key, String... values) {
        return directory.tell(player, key, values);
    }

    /** Asks {@code server} to take the particle and wings off {@code player}; empty when nobody answered. */
    public CompletableFuture<Optional<Result>> ask(String server, UUID player, ClearScope scope) {
        return calls.ask(server, TOPIC, Map.of("player", player.toString(), "scope", scope.name()), TIMEOUT)
                .thenApply(answer -> answer.map(Result::of));
    }

    public NetworkBus.Subscription answer(
            BiFunction<UUID, ClearScope, CompletableFuture<Result>> handler) {
        return calls.answerFrom(TOPIC, (from, question) -> {
            try {
                UUID player = UUID.fromString(question.get("player"));
                ClearScope scope = ClearScope.valueOf(question.get("scope"));
                if (!hostedHere(player)) {
                    return CompletableFuture.completedFuture(new Result(false, false).toAnswer());
                }
                return handler.apply(player, scope).thenApply(Result::toAnswer);
            } catch (RuntimeException malformed) {
                return CompletableFuture.completedFuture(new Result(false, false).toAnswer());
            }
        });
    }
}
