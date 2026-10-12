package de.raindancer.modules.arena.service;

import de.raindancer.core.platform.link.ServerLink;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The arena's end of RainsCore's server link to the survival server. Without a link the arena still runs — fights for
 * nothing between people already here — and anything that needs the other side fails at once, with a reason.
 */
public final class ArenaLink {

    public static final Duration ASK = Duration.ofSeconds(8);

    private final Supplier<Optional<ServerLink>> link;
    private final List<String> topics = new ArrayList<>();

    public ArenaLink(Supplier<Optional<ServerLink>> link) {
        this.link = link;
    }

    public boolean isUp() {
        return link.get().map(ServerLink::isConnected).orElse(false);
    }

    public CompletableFuture<Map<String, String>> ask(String topic, Map<String, String> data) {
        return link.get().map(live -> live.request(topic, data, ASK))
                .orElseGet(() -> CompletableFuture.failedFuture(new IllegalStateException("no server link")));
    }

    public void tell(String topic, Map<String, String> data) {
        link.get().ifPresent(live -> live.send(topic, data));
    }

    public void on(String topic, Function<Map<String, String>, Map<String, String>> handler) {
        link.get().ifPresent(live -> {
            live.on(topic, handler);
            topics.add(topic);
        });
    }

    /** Lets go of every topic this module handles — what stopping does, so the link keeps nothing of it. */
    public void close() {
        link.get().ifPresent(live -> topics.forEach(live::off));
        topics.clear();
    }
}
