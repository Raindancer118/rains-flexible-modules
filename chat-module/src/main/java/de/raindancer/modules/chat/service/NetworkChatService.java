package de.raindancer.modules.chat.service;

import de.raindancer.core.platform.link.ServerLink;
import de.raindancer.modules.chat.NetworkChatSettings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Public chat shared with the linked server.
 *
 * <p>A line goes over as it was drawn here — the sender's style, mentions, everything — so it looks the same on both
 * servers, and the other side only puts its tag in front. What goes is what the public saw: the listener hands over a
 * line only once nothing cancelled it, so a private chat, a channel, a filtered or a muted line never leaves.
 *
 * <p>A mute is per server, so the receiving side checks its own as well: somebody muted on the SMP is not heard there
 * by talking in the arena.
 */
public final class NetworkChatService {

    public static final String TOPIC = "chat.line";

    private static final GsonComponentSerializer GSON = GsonComponentSerializer.gson();

    private final Supplier<Optional<ServerLink>> link;
    private final Predicate<UUID> mutedHere;
    private final Consumer<Component> broadcast;
    private final BiFunction<String, Component, Component> tagged;
    private volatile NetworkChatSettings settings;

    /**
     * @param broadcast shows a line to everybody here, console included
     * @param tagged    the received line behind its server's tag, worded by messages.yml
     */
    public NetworkChatService(Supplier<Optional<ServerLink>> link, Predicate<UUID> mutedHere,
                              Consumer<Component> broadcast, BiFunction<String, Component, Component> tagged,
                              NetworkChatSettings settings) {
        this.link = link;
        this.mutedHere = mutedHere;
        this.broadcast = broadcast;
        this.tagged = tagged;
        this.settings = settings;
    }

    public void settings(NetworkChatSettings fresh) {
        this.settings = fresh;
    }

    public void listen() {
        link.get().ifPresent(live -> live.on(TOPIC, this::received));
    }

    public void stop() {
        link.get().ifPresent(live -> live.off(TOPIC));
    }

    /** A public line said here, once nothing can cancel it any more. */
    public void said(UUID sender, String name, String text, Component shown) {
        NetworkChatSettings now = settings;
        if (!now.enabled()) {
            return;
        }
        link.get().ifPresent(live -> live.send(TOPIC, Map.of(
                "uuid", sender.toString(),
                "name", name,
                "text", text,
                "tag", now.tagOrDefault(),
                "line", GSON.serialize(shown))));
    }

    private Map<String, String> received(Map<String, String> data) {
        if (!settings.enabled()) {
            return null;
        }
        try {
            UUID sender = UUID.fromString(data.getOrDefault("uuid", ""));
            if (mutedHere.test(sender)) {
                return null;
            }
            Component line = GSON.deserialize(data.getOrDefault("line", ""));
            broadcast.accept(tagged.apply(data.getOrDefault("tag", "Network"), line));
        } catch (RuntimeException notALine) {
            // A malformed line from the other side is one line nobody sees, not a broken chat.
        }
        return null;
    }
}
