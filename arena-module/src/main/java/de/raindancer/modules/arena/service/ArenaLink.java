package de.raindancer.modules.arena.service;

import de.raindancer.modules.arena.api.wire.Wire;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * The arena's end of the way to the survival servers — a {@link Wire}, plus knowing which survival server each player
 * belongs to. Without anybody to reach the arena still runs — fights for nothing between people already here — and
 * anything that needs the other side fails at once, with a reason.
 */
public final class ArenaLink {

    public static final Duration ASK = Wire.ASK;
    /** Money waits for a bank that may itself be slow to answer. */
    public static final Duration STAKE = Duration.ofSeconds(20);

    private final Wire wire;
    private final Function<UUID, Optional<String>> homes;
    private final Duration stakeWait;

    /** @param homes the survival server somebody was sent over from, as the arena knows it */
    public ArenaLink(Wire wire, Function<UUID, Optional<String>> homes) {
        this(wire, homes, STAKE);
    }

    public ArenaLink(Wire wire, Function<UUID, Optional<String>> homes, Duration stakeWait) {
        this.wire = wire;
        this.homes = homes;
        this.stakeWait = stakeWait;
    }

    /** How long money is waited for. */
    public Duration stakeWait() {
        return stakeWait;
    }

    public boolean isUp() {
        return wire.reachable();
    }

    /** The survival server this player belongs to; on a link the one there is. Empty when nobody sent them. */
    public Optional<String> homeOf(UUID player) {
        return homes.apply(player).or(wire::defaultPeer);
    }

    /** The one survival server there is — a link's; empty on a network, where somebody must say which. */
    public Optional<String> onlyPeer() {
        return wire.defaultPeer();
    }

    public CompletableFuture<Map<String, String>> ask(String peer, String topic, Map<String, String> data) {
        return wire.ask(peer, topic, data);
    }

    public CompletableFuture<Map<String, String>> ask(String peer, String topic, Map<String, String> data, Duration timeout) {
        return wire.ask(peer, topic, data, timeout);
    }

    /** Asks the survival server this player belongs to. */
    public CompletableFuture<Map<String, String>> askHome(UUID player, String topic, Map<String, String> data) {
        return homeOf(player).map(home -> wire.ask(home, topic, data)).orElseGet(() ->
                CompletableFuture.failedFuture(new IllegalStateException("no survival server known for " + player)));
    }

    public void tell(String peer, String topic, Map<String, String> data) {
        wire.tell(peer, topic, data);
    }

    public void tellHome(UUID player, String topic, Map<String, String> data) {
        homeOf(player).ifPresent(home -> wire.tell(home, topic, data));
    }

    public void on(String topic, Wire.Handler handler) {
        wire.on(topic, handler);
    }

    public CompletableFuture<Optional<Wire.Found>> find(String name) {
        return wire.find(name);
    }

    public Set<String> names() {
        return wire.names();
    }

    public void send(Player player, String server) {
        wire.send(player, server);
    }

    /** Lets go of every topic this module handles — what stopping does, so nothing of it stays behind. */
    public void close() {
        wire.close();
    }
}
