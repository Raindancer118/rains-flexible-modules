package de.raindancer.modules.playerutils.service;

import de.raindancer.core.moderation.vanish.Vanish;
import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.platform.network.Arrivals;
import de.raindancer.core.platform.network.NetworkBus;
import de.raindancer.core.platform.network.NetworkCalls;
import de.raindancer.core.platform.network.NetworkPlayers;
import de.raindancer.core.platform.network.NetworkServers;
import de.raindancer.core.platform.network.NetworkTravel;
import de.raindancer.core.platform.network.Presence;
import de.raindancer.modules.playerutils.PlayerUtilsSettings;
import org.bukkit.GameMode;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Reaching players on other servers of the network: where they are, where they stand, and going to them.
 *
 * <p>A player on another server is found by name in Core's presence (a vanished one only by somebody allowed to see
 * them). Where they stand is asked of their server, which answers only servers of the network and only about players
 * it hosts. To spectate across, the target's server is asked once for the place <em>and</em> told who is coming and
 * where they came from; it holds that for a minute, and when the staff member arrives the spectate flow resumes
 * there, with the way back leading to the server they left. Off a network nothing here finds anybody.
 */
public final class NetworkReach implements IPlayerUtilsService {

    public static final String LOCATE = "playerutils-locate";
    public static final String SPECTATE = "playerutils-spectate";

    /** How long a server waits for somebody it was told is coming. */
    static final Duration EXPECTED_FOR = Duration.ofSeconds(60);

    /** Somebody on another server. */
    public record Remote(UUID id, String name, String server) {
    }

    /** Where somebody stands. */
    public record Spot(String world, double x, double y, double z, float yaw, float pitch) {

        public Arrivals.Destination destination() {
            return new Arrivals.Destination(world, x, y, z, yaw, pitch);
        }
    }

    /** A staff member who is coming to spectate here, and where they come from. */
    public record Coming(UUID target, String fromServer, Spot from, GameMode mode, long expiresAt) {
    }

    /** Reads where a player of this server stands, on their own thread. */
    @FunctionalInterface
    public interface Locator {
        CompletableFuture<Optional<Spot>> locate(Player player);
    }

    private final Server server;
    private final Vanish vanish;
    private final Locator locator;
    private final LongSupplier clock;
    private final Map<UUID, Coming> expected = new ConcurrentHashMap<>();
    private volatile NetworkPlayers players;
    private volatile NetworkCalls calls;
    private volatile NetworkTravel travel;
    private volatile NetworkBus.Subscription locating;
    private volatile NetworkBus.Subscription spectating;
    private volatile Duration timeout = Duration.ofSeconds(4);

    public NetworkReach(Server server, Vanish vanish, Locator locator, LongSupplier clock) {
        this.server = server;
        this.vanish = vanish;
        this.locator = locator;
        this.clock = clock;
    }

    @Override
    public void settings(PlayerUtilsSettings fresh) {
        // Nothing here reads a setting.
    }

    public void connect(NetworkPlayers directory, NetworkCalls network, NetworkTravel hop) {
        this.players = directory;
        this.calls = network;
        this.travel = hop;
        this.locating = network.answerFrom(LOCATE, this::locatedHere);
        this.spectating = network.answerFrom(SPECTATE, this::spectatedHere);
    }

    public void disconnect() {
        for (NetworkBus.Subscription one : new NetworkBus.Subscription[]{locating, spectating}) {
            if (one != null) {
                one.close();
            }
        }
        locating = null;
        spectating = null;
        players = null;
        calls = null;
        travel = null;
        expected.clear();
    }

    void timeout(Duration fresh) {
        this.timeout = fresh;
    }

    public boolean isNetwork() {
        return players != null;
    }

    /** This server's id on the network, blank off one. */
    public String self() {
        NetworkPlayers network = players;
        return network == null ? "" : network.self();
    }

    /**
     * Somebody online on another server who is not also meant by a name on this one. A vanished player is found only
     * by somebody allowed to see them.
     */
    public Optional<Remote> find(CommandSender sender, String typed) {
        NetworkPlayers network = players;
        if (network == null || typed == null || typed.isBlank() || PlayerTargets.isSelector(typed)
                || !PlayerTargets.lookup(server, sender, typed).online().isEmpty()) {
            return Optional.empty();
        }
        boolean mayKnowVanished = !(sender instanceof Player viewer) || vanish.maySeeVanished(viewer.getUniqueId());
        return network.named(typed, mayKnowVanished).filter(there -> !there.server().equals(network.self()))
                .map(NetworkReach::remote);
    }

    private static Remote remote(Presence.Here there) {
        return new Remote(there.player(), there.name(), there.server());
    }

    /** Where they stand, asked of their server. Empty when it cannot be told. */
    public CompletableFuture<Optional<Spot>> locate(Remote who) {
        NetworkCalls network = calls;
        if (network == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return network.ask(who.server(), LOCATE, Map.of("player", who.id().toString()), timeout)
                .thenApply(answer -> answer.flatMap(NetworkReach::spotOf));
    }

    /**
     * Where the target stands, and the target's server told that {@code viewer} is coming from {@code from} to watch.
     * Empty when the server did not agree.
     */
    public CompletableFuture<Optional<Spot>> prepareSpectate(Remote target, UUID viewer, Spot from, GameMode mode,
                                                             String returnServer) {
        NetworkCalls network = calls;
        if (network == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        Map<String, String> question = new java.util.LinkedHashMap<>(Map.of("player", target.id().toString(),
                "viewer", viewer.toString(), "mode", mode.name(), "returnServer", returnServer));
        question.putAll(write("from", from));
        return network.ask(target.server(), SPECTATE, question, timeout)
                .thenApply(answer -> answer.flatMap(NetworkReach::spotOf));
    }

    /** Tells the target's server nobody is coming after all. */
    public void cancelSpectate(Remote target, UUID viewer) {
        NetworkCalls network = calls;
        if (network != null) {
            network.ask(target.server(), SPECTATE, Map.of("cancel", "true", "viewer", viewer.toString()), timeout);
        }
    }

    /** Sends a player to a place on another server. */
    public CompletableFuture<Boolean> travel(Player player, String serverId, Spot to) {
        NetworkTravel hop = travel;
        return hop == null ? CompletableFuture.completedFuture(false)
                : hop.travel(player, serverId, Optional.of(to.destination()));
    }

    /** Who was said to be coming to watch, once; empty when nobody was or it ran out. */
    public Optional<Coming> takeComing(UUID viewer) {
        Coming coming = expected.remove(viewer);
        return coming != null && coming.expiresAt() >= clock.getAsLong() ? Optional.of(coming) : Optional.empty();
    }

    private boolean fromTheNetwork(String from) {
        NetworkPlayers network = players;
        return network != null && !from.equals(network.self())
                && network.servers().stream().map(NetworkServers.Server::id).anyMatch(from::equals);
    }

    private Player hosted(Map<String, String> question) {
        try {
            return server.getPlayer(UUID.fromString(question.getOrDefault("player", "")));
        } catch (IllegalArgumentException garbled) {
            return null;
        }
    }

    private static final Map<String, String> NOTHING = Map.of("found", "false");

    private CompletableFuture<Map<String, String>> locatedHere(String from, Map<String, String> question) {
        Player here = fromTheNetwork(from) ? hosted(question) : null;
        if (here == null) {
            return CompletableFuture.completedFuture(NOTHING);
        }
        return locator.locate(here).thenApply(spot -> spot.map(NetworkReach::answer).orElse(NOTHING));
    }

    private CompletableFuture<Map<String, String>> spectatedHere(String from, Map<String, String> question) {
        if (!fromTheNetwork(from)) {
            return CompletableFuture.completedFuture(NOTHING);
        }
        UUID viewer;
        try {
            viewer = UUID.fromString(question.getOrDefault("viewer", ""));
        } catch (IllegalArgumentException garbled) {
            return CompletableFuture.completedFuture(NOTHING);
        }
        if ("true".equals(question.get("cancel"))) {
            expected.remove(viewer);
            return CompletableFuture.completedFuture(Map.of("found", "false"));
        }
        Player target = hosted(question);
        Optional<Spot> back = spotOf(question, "from");
        if (target == null || back.isEmpty()) {
            return CompletableFuture.completedFuture(NOTHING);
        }
        GameMode mode;
        try {
            mode = GameMode.valueOf(question.getOrDefault("mode", "SURVIVAL").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException garbled) {
            mode = GameMode.SURVIVAL;
        }
        GameMode returning = mode == GameMode.SPECTATOR ? GameMode.SURVIVAL : mode;
        return locator.locate(target).thenApply(spot -> {
            if (spot.isEmpty()) {
                return NOTHING;
            }
            expected.put(viewer, new Coming(target.getUniqueId(), question.getOrDefault("returnServer", from),
                    back.get(), returning, clock.getAsLong() + EXPECTED_FOR.toMillis()));
            return answer(spot.get());
        });
    }

    private static Map<String, String> answer(Spot spot) {
        Map<String, String> reply = new java.util.LinkedHashMap<>(write("", spot));
        reply.put("found", "true");
        return reply;
    }

    private static Map<String, String> write(String prefix, Spot spot) {
        String p = prefix.isEmpty() ? "" : prefix + ".";
        return Map.of(p + "world", spot.world(), p + "x", String.valueOf(spot.x()), p + "y", String.valueOf(spot.y()),
                p + "z", String.valueOf(spot.z()), p + "yaw", String.valueOf(spot.yaw()),
                p + "pitch", String.valueOf(spot.pitch()));
    }

    private static Optional<Spot> spotOf(Map<String, String> reply) {
        return "true".equals(reply.get("found")) ? spotOf(reply, "") : Optional.empty();
    }

    private static Optional<Spot> spotOf(Map<String, String> data, String prefix) {
        String p = prefix.isEmpty() ? "" : prefix + ".";
        try {
            return Optional.of(new Spot(data.get(p + "world"), Double.parseDouble(data.get(p + "x")),
                    Double.parseDouble(data.get(p + "y")), Double.parseDouble(data.get(p + "z")),
                    Float.parseFloat(data.get(p + "yaw")), Float.parseFloat(data.get(p + "pitch"))));
        } catch (RuntimeException garbled) {
            return Optional.empty();
        }
    }

    @Override
    public String describe() {
        return "finding, locating and going to players on other servers";
    }
}
