package de.raindancer.modules.essentials.service;

import de.raindancer.core.moderation.vanish.Vanish;
import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.platform.network.NetworkBus;
import de.raindancer.core.platform.network.NetworkCalls;
import de.raindancer.core.platform.network.NetworkPlayers;
import de.raindancer.core.platform.network.NetworkServers;
import de.raindancer.core.platform.network.Presence;
import de.raindancer.modules.essentials.EssentialsSettings;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Who is online and where, across the servers of a network — for {@code /list}, {@code /where} and
 * {@code /seen}.
 *
 * <p>This server's own players are read live and filtered by {@link Vanish}; everybody else comes from Core's
 * presence, where a vanished player is only found by somebody allowed to see them. Off a network there is no
 * presence and every answer is about this server alone. Where somebody stands on another server is asked of
 * that server ({@link #WHERE}), which answers only servers of the network and only for players it hosts.
 */
public final class NetworkWho implements IEssentialsService {

    public static final String WHERE = "essentials.where";

    /** Somebody online. */
    public record Listed(UUID id, String name, boolean vanished) {
    }

    /** Everybody on one server; {@code server} is blank off a network. */
    public record Group(String server, boolean self, List<Listed> players) {
    }

    /** Somebody found online: which server, and whether that is this one. */
    public record Found(UUID id, String name, String server, boolean self, boolean vanished) {
    }

    /** Where somebody stands. */
    public record Spot(String world, long x, long y, long z) {
    }

    /** Reads where a player of this server stands, on their own thread. */
    @FunctionalInterface
    public interface Locator {
        CompletableFuture<Optional<Spot>> locate(Player player);
    }

    private final Server server;
    private final Vanish vanish;
    private final Locator locator;
    private volatile NetworkPlayers players;
    private volatile NetworkCalls calls;
    private volatile NetworkBus.Subscription answering;
    private volatile Duration timeout = Duration.ofSeconds(4);

    public NetworkWho(Server server, Vanish vanish, Locator locator) {
        this.server = server;
        this.vanish = vanish;
        this.locator = locator;
    }

    @Override
    public void settings(EssentialsSettings fresh) {
        // Nothing here reads a setting.
    }

    public void connect(NetworkPlayers directory, NetworkCalls network) {
        this.players = directory;
        this.calls = network;
        this.answering = network.answerFrom(WHERE, this::answerWhere);
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

    /** Whether {@code sender} may know about vanished players: the console, and whoever Core lets see them. */
    public boolean mayLookIntoVanish(CommandSender sender) {
        return !(sender instanceof Player viewer) || vanish.maySeeVanished(viewer.getUniqueId());
    }

    // ---------------------------------------------------------------------------------- /list

    /** Everybody {@code sender} may know is online, this server first, the others by name. Off a network: this server only. */
    public List<Group> everybody(CommandSender sender) {
        NetworkPlayers network = players;
        String self = network == null ? "" : network.self();
        List<Group> groups = new ArrayList<>();
        groups.add(new Group(self, true, hereFor(sender)));
        if (network == null) {
            return groups;
        }
        Map<String, List<Presence.Here>> byServer = network.byServer(mayLookIntoVanish(sender));
        byServer.entrySet().stream().filter(entry -> !entry.getKey().equals(self))
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER)).forEach(entry -> groups.add(
                        new Group(entry.getKey(), false, entry.getValue().stream()
                                .map(there -> new Listed(there.player(), there.name(), there.vanished()))
                                .sorted(Comparator.comparing(Listed::name, String.CASE_INSENSITIVE_ORDER)).toList())));
        return groups;
    }

    private List<Listed> hereFor(CommandSender sender) {
        List<Listed> here = new ArrayList<>();
        for (Player online : server.getOnlinePlayers()) {
            if (sender instanceof Player viewer && !vanish.canSee(viewer.getUniqueId(), online.getUniqueId())) {
                continue;
            }
            here.add(new Listed(online.getUniqueId(), online.getName(), vanish.isVanished(online.getUniqueId())));
        }
        here.sort(Comparator.comparing(Listed::name, String.CASE_INSENSITIVE_ORDER));
        return here;
    }

    // ------------------------------------------------------------------------- /where and /seen

    /** Somebody online, here or on another server, as far as {@code sender} may know. Empty when nobody is. */
    public Optional<Found> find(CommandSender sender, String typed) {
        if (typed == null || typed.isBlank() || PlayerTargets.isSelector(typed)) {
            return Optional.empty();
        }
        for (Player candidate : PlayerTargets.lookup(server, sender, typed).online()) {
            boolean visible = !(sender instanceof Player viewer)
                    || vanish.canSee(viewer.getUniqueId(), candidate.getUniqueId());
            if (visible) {
                return Optional.of(new Found(candidate.getUniqueId(), candidate.getName(), self(), true,
                        vanish.isVanished(candidate.getUniqueId())));
            }
        }
        NetworkPlayers network = players;
        if (network == null) {
            return Optional.empty();
        }
        return network.named(typed, mayLookIntoVanish(sender)).filter(there -> !there.server().equals(network.self()))
                .map(NetworkWho::found);
    }

    /** Somebody online on another server, by id — for {@code /seen}. */
    public Optional<Found> elsewhere(CommandSender sender, UUID player) {
        NetworkPlayers network = players;
        if (network == null || player == null) {
            return Optional.empty();
        }
        return network.where(player, mayLookIntoVanish(sender)).filter(there -> !there.server().equals(network.self()))
                .map(NetworkWho::found);
    }

    /** Names on other servers {@code sender} may know, to complete. */
    public Set<String> namesElsewhere(CommandSender sender) {
        NetworkPlayers network = players;
        if (network == null) {
            return Set.of();
        }
        return network.everyone(mayLookIntoVanish(sender)).stream().filter(there -> !there.server().equals(network.self()))
                .map(Presence.Here::name).collect(Collectors.toSet());
    }

    private static Found found(Presence.Here there) {
        return new Found(there.player(), there.name(), there.server(), false, there.vanished());
    }

    private String self() {
        NetworkPlayers network = players;
        return network == null ? "" : network.self();
    }

    /** Where {@code who} stands: read here, or asked of their server. Empty when it cannot be told. */
    public CompletableFuture<Optional<Spot>> locate(Found who) {
        if (who.self()) {
            Player here = server.getPlayer(who.id());
            return here == null ? CompletableFuture.completedFuture(Optional.empty()) : locator.locate(here);
        }
        NetworkCalls network = calls;
        if (network == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return network.ask(who.server(), WHERE, Map.of("player", who.id().toString()), timeout).thenApply(answer ->
                answer.filter(reply -> "true".equals(reply.get("found"))).flatMap(NetworkWho::spotOf));
    }

    private static Optional<Spot> spotOf(Map<String, String> reply) {
        try {
            return Optional.of(new Spot(reply.get("world"), Long.parseLong(reply.get("x")),
                    Long.parseLong(reply.get("y")), Long.parseLong(reply.get("z"))));
        } catch (RuntimeException garbled) {
            return Optional.empty();
        }
    }

    private CompletableFuture<Map<String, String>> answerWhere(String from, Map<String, String> question) {
        NetworkPlayers network = players;
        boolean ours = network != null && network.servers().stream().map(NetworkServers.Server::id).anyMatch(from::equals);
        UUID asked;
        try {
            asked = UUID.fromString(question.getOrDefault("player", ""));
        } catch (IllegalArgumentException garbled) {
            return CompletableFuture.completedFuture(Map.of("found", "false"));
        }
        Player here = ours ? server.getPlayer(asked) : null;
        if (here == null) {
            return CompletableFuture.completedFuture(Map.of("found", "false"));
        }
        return locator.locate(here).thenApply(spot -> spot.<Map<String, String>>map(at -> Map.of("found", "true",
                "world", at.world(), "x", String.valueOf(at.x()), "y", String.valueOf(at.y()),
                "z", String.valueOf(at.z()))).orElse(Map.of("found", "false")));
    }

    @Override
    public String describe() {
        return "who is online and where, across the network";
    }
}
