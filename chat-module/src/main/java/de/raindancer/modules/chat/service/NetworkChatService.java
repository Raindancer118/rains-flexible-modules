package de.raindancer.modules.chat.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.raindancer.core.platform.link.ServerLink;
import de.raindancer.core.platform.network.NetworkBus;
import de.raindancer.core.platform.network.NetworkEvent;
import de.raindancer.modules.chat.NetworkChatSettings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Public chat shared with the other servers: every server of a network over {@link NetworkBus}, or on a server
 * that is not on one the single linked server over the 1.x link.
 *
 * <p>A line goes over as it was drawn here — the sender's style, mentions, everything — so it looks the same on both
 * servers, and the other side only puts its tag in front. What goes is what the public saw: the listener hands over a
 * line only once nothing cancelled it, so a private chat, a channel, a filtered or a muted line never leaves.
 *
 * <p>A mute is per server, so the receiving side checks its own as well: somebody muted on the SMP is not heard there
 * by talking in the arena.
 *
 * <p>On a network a line is published once and every other server hears it (a server does not hear itself),
 * so it is no longer one linked server that gets it.
 */
public final class NetworkChatService {

    public static final String TOPIC = "chat.line";

    private static final Gson JSON = new Gson();
    private static final GsonComponentSerializer GSON = GsonComponentSerializer.gson();

    private final Supplier<Optional<ServerLink>> link;
    private final NetworkBus bus;
    private final BooleanSupplier onNetwork;
    private final Predicate<UUID> mutedHere;
    private final Consumer<Component> broadcast;
    private final BiFunction<String, Component, Component> tagged;
    private volatile NetworkChatSettings settings;
    private volatile NetworkBus.Subscription subscription;

    /**
     * @param broadcast shows a line to everybody here, console included
     * @param tagged    the received line behind its server's tag, worded by messages.yml
     */
    public NetworkChatService(Supplier<Optional<ServerLink>> link, Predicate<UUID> mutedHere,
                              Consumer<Component> broadcast, BiFunction<String, Component, Component> tagged,
                              NetworkChatSettings settings) {
        this(link, NetworkBus.NONE, () -> false, mutedHere, broadcast, tagged, settings);
    }

    /**
     * @param onNetwork whether this server is on a network: then {@code bus} carries the chat and the link is not used
     */
    public NetworkChatService(Supplier<Optional<ServerLink>> link, NetworkBus bus, BooleanSupplier onNetwork,
                              Predicate<UUID> mutedHere, Consumer<Component> broadcast,
                              BiFunction<String, Component, Component> tagged, NetworkChatSettings settings) {
        this.link = link;
        this.bus = bus;
        this.onNetwork = onNetwork;
        this.mutedHere = mutedHere;
        this.broadcast = broadcast;
        this.tagged = tagged;
        this.settings = settings;
    }

    public void settings(NetworkChatSettings fresh) {
        this.settings = fresh;
    }

    public void listen() {
        if (onNetwork.getAsBoolean()) {
            subscription = bus.subscribe(TOPIC, this::heard);
            return;
        }
        link.get().ifPresent(live -> live.on(TOPIC, this::received));
    }

    public void stop() {
        NetworkBus.Subscription heard = subscription;
        if (heard != null) {
            heard.close();
            subscription = null;
        }
        link.get().ifPresent(live -> live.off(TOPIC));
    }

    /** A public line said here, once nothing can cancel it any more. */
    public void said(UUID sender, String name, String text, Component shown) {
        NetworkChatSettings now = settings;
        if (!now.enabled()) {
            return;
        }
        if (onNetwork.getAsBoolean()) {
            JsonObject payload = new JsonObject();
            payload.addProperty("name", name);
            payload.addProperty("text", text);
            payload.addProperty("tag", tagOnNetwork(now));
            payload.addProperty("line", GSON.serialize(shown));
            bus.publish(TOPIC, sender.toString(), JSON.toJson(payload));
            return;
        }
        link.get().ifPresent(live -> live.send(TOPIC, Map.of(
                "uuid", sender.toString(),
                "name", name,
                "text", text,
                "tag", now.tagOrDefault(),
                "line", GSON.serialize(shown))));
    }

    /** A server with no tag of its own is known by its id, which tells servers apart where "Network" would not. */
    private String tagOnNetwork(NetworkChatSettings now) {
        return now.tag() == null || now.tag().isBlank() ? (bus.server().isBlank() ? "Network" : bus.server())
                : now.tag().strip();
    }

    private void heard(NetworkEvent event) {
        try {
            JsonObject payload = JsonParser.parseString(event.payload()).getAsJsonObject();
            received(Map.of("uuid", event.key(), "tag", payload.get("tag").getAsString(),
                    "line", payload.get("line").getAsString()));
        } catch (RuntimeException notALine) {
            // A malformed line from another server is one line nobody sees, not a broken chat.
        }
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
