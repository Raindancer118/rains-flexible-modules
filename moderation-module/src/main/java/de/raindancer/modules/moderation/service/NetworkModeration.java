package de.raindancer.modules.moderation.service;

import de.raindancer.core.platform.network.NetworkBus;
import de.raindancer.core.platform.network.NetworkCalls;
import de.raindancer.core.platform.network.NetworkPlayers;
import de.raindancer.core.platform.network.NetworkServers;
import de.raindancer.core.platform.network.Presence;
import de.raindancer.modules.moderation.ModerationSettings;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Acting on a player who is on another server of the network: finding them, throwing them off, telling them.
 *
 * <p>The staff member's permission and the {@code StaffRule} are checked on the server they type on; the server the
 * player is on does the kicking, and only when the question comes from a server of the network and the player is
 * really on it. Off a network every method answers "nobody elsewhere".
 */
public final class NetworkModeration implements IModerationService {

    public static final String KICK = "moderation-kick";

    /** Throws a player of this server off, on their own thread; {@code ban} says it is for a ban. */
    @FunctionalInterface
    public interface Kicker {
        CompletableFuture<Boolean> kick(Player player, String reason, boolean ban);
    }

    /** Somebody on another server. */
    public record There(UUID id, String name, String server, boolean vanished) {
    }

    private final Server server;
    private final Kicker kicker;
    private volatile NetworkPlayers players;
    private volatile NetworkCalls calls;
    private volatile NetworkBus.Subscription answering;
    private volatile Duration timeout = Duration.ofSeconds(4);

    public NetworkModeration(Server server, Kicker kicker) {
        this.server = server;
        this.kicker = kicker;
    }

    @Override
    public void settings(ModerationSettings settings) {
        // Nothing here reads a setting.
    }

    public void connect(NetworkPlayers directory, NetworkCalls network) {
        this.players = directory;
        this.calls = network;
        this.answering = network.answerFrom(KICK, this::kickedHere);
    }

    public void disconnect() {
        NetworkBus.Subscription current = answering;
        if (current != null) {
            current.close();
        }
        answering = null;
        players = null;
        calls = null;
    }

    void timeout(Duration fresh) {
        this.timeout = fresh;
    }

    public boolean isNetwork() {
        return players != null;
    }

    /** The other server somebody is on; a vanished player is found only {@code withVanished}, for staff who may see them. */
    public Optional<There> whereIs(UUID player, boolean withVanished) {
        NetworkPlayers network = players;
        if (network == null || player == null) {
            return Optional.empty();
        }
        return network.where(player, withVanished).filter(there -> !there.server().equals(network.self()))
                .map(NetworkModeration::there);
    }

    /** Everybody on other servers that {@code withVanished} lets through. */
    public List<There> everyoneElse(boolean withVanished) {
        NetworkPlayers network = players;
        if (network == null) {
            return List.of();
        }
        return network.everyone(withVanished).stream().filter(there -> !there.server().equals(network.self()))
                .map(NetworkModeration::there).toList();
    }

    /** Staff on other servers: those on {@code roster}, the network's own list of who is staff. */
    public List<There> staffElsewhere(boolean withVanished, java.util.function.Predicate<UUID> roster) {
        return everyoneElse(withVanished).stream().filter(there -> roster.test(there.id())).toList();
    }

    private static There there(Presence.Here here) {
        return new There(here.player(), here.name(), here.server(), here.vanished());
    }

    /**
     * Asks their server to throw them off.
     *
     * @return whether it did; false when nobody answered or they were no longer there
     */
    public CompletableFuture<Boolean> kick(There there, String reason, boolean ban) {
        NetworkCalls network = calls;
        if (network == null) {
            return CompletableFuture.completedFuture(false);
        }
        return network.ask(there.server(), KICK, Map.of("player", there.id().toString(),
                "reason", reason == null ? "" : reason, "ban", String.valueOf(ban)), timeout)
                .thenApply(answer -> answer.map(reply -> "true".equals(reply.get("kicked"))).orElse(false));
    }

    /** Throws somebody off wherever they are, if they are on another server, vanished or not; nobody waits for the answer. */
    public void kickAnywhere(UUID player, String reason, boolean ban) {
        whereIs(player, true).ifPresent(there -> kick(there, reason, ban));
    }

    /** Says a message key to somebody on whichever server they are on; false when it could not reach them. */
    public boolean tell(UUID player, String key, String... values) {
        NetworkPlayers network = players;
        return network != null && network.tell(player, key, values);
    }

    private CompletableFuture<Map<String, String>> kickedHere(String from, Map<String, String> question) {
        NetworkPlayers network = players;
        boolean ours = network != null && network.servers().stream().map(NetworkServers.Server::id)
                .anyMatch(from::equals) && !from.equals(network.self());
        Player here = null;
        if (ours) {
            try {
                here = server.getPlayer(UUID.fromString(question.getOrDefault("player", "")));
            } catch (IllegalArgumentException garbled) {
                here = null;
            }
        }
        if (here == null) {
            return CompletableFuture.completedFuture(Map.of("kicked", "false"));
        }
        return kicker.kick(here, question.getOrDefault("reason", ""), "true".equals(question.get("ban")))
                .thenApply(done -> Map.of("kicked", String.valueOf(done)));
    }

    @Override
    public String describe() {
        return "acting on players who are on another server of the network";
    }
}
