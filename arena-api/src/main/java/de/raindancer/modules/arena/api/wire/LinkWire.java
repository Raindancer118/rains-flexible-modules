package de.raindancer.modules.arena.api.wire;

import de.raindancer.core.platform.link.ServerLink;
import de.raindancer.core.platform.proxy.ProxyTransfer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The 1.x way: one {@link ServerLink} to the one other server, a plain proxy transfer to move somebody. Without a link
 * everything that needs the other end fails at once, with a reason.
 */
public final class LinkWire implements Wire {

    /** The topic the survival side answers a name on — the link has no presence of its own. */
    private static final String WHERE = "arena.where";
    /** A handler on a link thread may wait this long for a later answer; the asker waits longer. */
    private static final long LATER_SECONDS = 10;

    private final Supplier<Optional<ServerLink>> link;
    private final Plugin plugin;
    private final Supplier<Set<String>> elsewhere;
    private final List<String> topics = new ArrayList<>();

    public LinkWire(Supplier<Optional<ServerLink>> link, Plugin plugin, Supplier<Set<String>> elsewhere) {
        this.link = link;
        this.plugin = plugin;
        this.elsewhere = elsewhere;
    }

    @Override
    public String self() {
        return "";
    }

    @Override
    public Optional<String> defaultPeer() {
        return Optional.of("");
    }

    @Override
    public boolean reachable() {
        return link.get().map(ServerLink::isConnected).orElse(false);
    }

    @Override
    public boolean reachable(String peer) {
        return reachable();
    }

    @Override
    public CompletableFuture<Map<String, String>> ask(String peer, String topic, Map<String, String> data,
                                                      Duration timeout) {
        return link.get().map(live -> live.request(topic, data, timeout))
                .orElseGet(() -> CompletableFuture.failedFuture(new IllegalStateException("no server link")));
    }

    @Override
    public void tell(String peer, String topic, Map<String, String> data) {
        link.get().ifPresent(live -> live.send(topic, data));
    }

    @Override
    public synchronized void on(String topic, Handler handler) {
        link.get().ifPresent(live -> {
            live.on(topic, data -> handler.handle("", data));
            topics.add(topic);
        });
    }

    @Override
    public synchronized void onLater(String topic, LaterHandler handler) {
        link.get().ifPresent(live -> {
            // Link handlers run on the link's own threads, where waiting is what they are for.
            live.on(topic, data -> {
                try {
                    return handler.handle("", data).get(LATER_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return Map.of("ok", "false");
                } catch (Exception failed) {
                    return Map.of("ok", "false");
                }
            });
            topics.add(topic);
        });
    }

    @Override
    public CompletableFuture<Optional<Found>> find(String name) {
        return ask("", WHERE, Map.of("name", name)).handle((answer, failure) -> {
            if (failure != null || !"true".equals(answer.get("online"))) {
                return Optional.<Found>empty();
            }
            return Optional.of(new Found(UUID.fromString(answer.get("player")), answer.getOrDefault("name", name), ""));
        });
    }

    @Override
    public Set<String> names() {
        return elsewhere.get();
    }

    @Override
    public void send(Player player, String server) {
        ProxyTransfer.send(plugin, player, server);
    }

    @Override
    public synchronized void close() {
        link.get().ifPresent(live -> topics.forEach(live::off));
        topics.clear();
    }
}
