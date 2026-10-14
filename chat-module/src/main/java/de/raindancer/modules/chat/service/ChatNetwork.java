package de.raindancer.modules.chat.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.raindancer.core.platform.network.NetworkBus;
import de.raindancer.core.platform.network.NetworkCalls;
import de.raindancer.core.platform.network.NetworkEvent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * What staff do to chat, on every server of a network: {@code /announce}, {@code /ad}, {@code /chat clear},
 * {@code freeze} and {@code slowmode}. Each call does it here through the {@link Receiver} and, on a network, tells
 * the other servers, which do the same through theirs — each worded by its own messages. The permission was checked
 * on the server the command was typed on and is not asked again — but whoever an event names must really be on the
 * server that sent it, so no server can speak for a player on another. No player named means that server's console.
 *
 * <p>A freeze or slowmode travels as the state it sets, never as a toggle, so a server that missed one cannot end
 * up opposite to the rest. A server that starts later asks the others for the state ({@link #catchUp}) and takes
 * the newest answer.
 */
public final class ChatNetwork {

    public static final String ANNOUNCE = "chat.announce";
    public static final String CONTROL = "chat.control";
    public static final String STATE = "chat.state";

    private static final Gson JSON = new Gson();
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final int LONGEST_SLOWMODE = 60;
    private static final String CONSOLE = "CONSOLE";

    /** Freeze and slowmode as they stand; {@code slowmode} is -1 while the settings' default is in force. */
    public record State(boolean frozen, int slowmode) {
    }

    /** What happens on this server — the same for a command typed here and for one heard from another server. */
    public interface Receiver {

        void banner(String text);

        void ad(UUID sender, String name, String text);

        void clear(String by);

        void freeze(boolean frozen, String by);

        /** @param seconds -1 for off */
        void slowmode(int seconds, String by);

        State state();

        /** Takes over a state learnt from another server, without announcing it. */
        void adopt(State state);
    }

    private final NetworkBus bus;
    private final Supplier<Optional<NetworkCalls>> calls;
    private final BooleanSupplier onNetwork;
    private final Supplier<List<String>> otherServers;
    private final Predicate<UUID> mutedHere;
    private final BiPredicate<UUID, String> playerOn;
    private final Receiver receiver;
    private final LongSupplier clock;
    private final Rejected rejected = new Rejected();
    private final List<NetworkBus.Subscription> subscriptions = new ArrayList<>();
    private long changedAt;

    /**
     * @param otherServers the ids of the other servers online now, whom a starting server asks for the state
     * @param mutedHere    whether somebody is muted on this server: their ad is not shown here
     * @param playerOn     whether a player is, by the network's presence, on the server with that id
     */
    public ChatNetwork(NetworkBus bus, Supplier<Optional<NetworkCalls>> calls, BooleanSupplier onNetwork,
                       Supplier<List<String>> otherServers, Predicate<UUID> mutedHere,
                       BiPredicate<UUID, String> playerOn, Receiver receiver, LongSupplier clock) {
        this.bus = bus;
        this.calls = calls;
        this.onNetwork = onNetwork;
        this.otherServers = otherServers;
        this.mutedHere = mutedHere;
        this.playerOn = playerOn;
        this.receiver = receiver;
        this.clock = clock;
    }

    public void listen() {
        if (!onNetwork.getAsBoolean()) {
            return;
        }
        subscriptions.add(bus.subscribe(ANNOUNCE, this::announced));
        subscriptions.add(bus.subscribe(CONTROL, this::controlled));
        subscriptions.add(bus.onResync(() -> catchUp()));
        calls.get().ifPresent(live -> subscriptions.add(live.answer(STATE, question -> {
            State now = receiver.state();
            synchronized (this) {
                return Map.of("frozen", String.valueOf(now.frozen()), "slowmode", String.valueOf(now.slowmode()),
                        "at", String.valueOf(changedAt));
            }
        })));
    }

    public void stop() {
        subscriptions.forEach(NetworkBus.Subscription::close);
        subscriptions.clear();
    }

    // ------------------------------------------------------------------ saying it here and everywhere

    /** @param by who announces; null for the console */
    public void announce(UUID by, String text) {
        receiver.banner(text);
        JsonObject payload = payload("text", text);
        issuer(payload, by, null);
        publish(ANNOUNCE, "banner", payload);
    }

    public void ad(UUID sender, String name, String text) {
        receiver.ad(sender, name, text);
        JsonObject payload = payload("text", text);
        payload.addProperty("name", name);
        publish(ANNOUNCE, "ad:" + sender, payload);
    }

    /** @param byId who does it; null for the console */
    public void clear(UUID byId, String by) {
        receiver.clear(by);
        JsonObject payload = new JsonObject();
        issuer(payload, byId, by);
        publish(CONTROL, "clear", payload);
    }

    public void freeze(boolean frozen, UUID byId, String by) {
        receiver.freeze(frozen, by);
        changed();
        JsonObject payload = new JsonObject();
        issuer(payload, byId, by);
        payload.addProperty("value", frozen);
        publish(CONTROL, "freeze", payload);
    }

    /** @param seconds -1 for off */
    public void slowmode(int seconds, UUID byId, String by) {
        receiver.slowmode(seconds, by);
        changed();
        JsonObject payload = new JsonObject();
        issuer(payload, byId, by);
        payload.addProperty("value", seconds);
        publish(CONTROL, "slowmode", payload);
    }

    private static JsonObject payload(String name, String value) {
        JsonObject payload = new JsonObject();
        payload.addProperty(name, value);
        return payload;
    }

    private static void issuer(JsonObject payload, UUID id, String name) {
        if (id == null) {
            payload.addProperty("console", true);
        } else {
            payload.addProperty("byId", id.toString());
        }
        if (name != null) {
            payload.addProperty("by", name);
        }
    }

    private void publish(String topic, String key, JsonObject payload) {
        if (onNetwork.getAsBoolean()) {
            bus.publish(topic, key, JSON.toJson(payload));
        }
    }

    private synchronized void changed() {
        changedAt = clock.getAsLong();
    }

    // ------------------------------------------------------------------ hearing it

    private void announced(NetworkEvent event) {
        try {
            JsonObject payload = JsonParser.parseString(event.payload()).getAsJsonObject();
            String text = payload.get("text").getAsString();
            if (event.key().equals("banner")) {
                if (issuer(event, payload).isPresent()) {
                    receiver.banner(text);
                }
            } else if (event.key().startsWith("ad:")) {
                UUID sender = UUID.fromString(event.key().substring(3));
                if (!playerOn.test(sender, event.server())) {
                    rejected.of(event.server(), kind(event));
                } else if (!mutedHere.test(sender)) {
                    receiver.ad(sender, payload.get("name").getAsString(), text);
                }
            }
        } catch (RuntimeException notAnAnnouncement) {
            // A malformed announcement from another server is one nobody sees, not a broken chat.
        }
    }

    private void controlled(NetworkEvent event) {
        try {
            JsonObject payload = JsonParser.parseString(event.payload()).getAsJsonObject();
            Optional<String> issuer = issuer(event, payload);
            if (issuer.isEmpty()) {
                return;
            }
            String by = issuer.get();
            switch (event.key()) {
                case "clear" -> receiver.clear(by);
                case "freeze" -> {
                    receiver.freeze(payload.get("value").getAsBoolean(), by);
                    changed();
                }
                case "slowmode" -> {
                    int seconds = payload.get("value").getAsInt();
                    if (seconds >= -1 && seconds <= LONGEST_SLOWMODE) {
                        receiver.slowmode(seconds, by);
                        changed();
                    }
                }
                default -> {
                }
            }
        } catch (RuntimeException notAControl) {
            // A malformed control from another server changes nothing.
        }
    }

    private static String kind(NetworkEvent event) {
        int colon = event.key().indexOf(':');
        return event.topic() + " " + (colon < 0 ? event.key() : event.key().substring(0, colon));
    }

    /** @return who did it, as to show; empty when the event names a player who is not on the server that sent it */
    private Optional<String> issuer(NetworkEvent event, JsonObject payload) {
        if (payload.has("byId")) {
            UUID who = UUID.fromString(payload.get("byId").getAsString());
            if (playerOn.test(who, event.server())) {
                return Optional.of(payload.get("by") == null ? who.toString() : payload.get("by").getAsString());
            }
        } else if (payload.has("console") && payload.get("console").getAsBoolean()) {
            return Optional.of(CONSOLE);
        }
        rejected.of(event.server(), kind(event));
        return Optional.empty();
    }

    // ------------------------------------------------------------------ starting late

    /**
     * Asks the other servers for their freeze and slowmode and takes the newest answer, if it is newer than anything
     * this server did or heard itself. Completes once everybody answered or the timeout passed.
     */
    public CompletableFuture<Void> catchUp() {
        Optional<NetworkCalls> live = calls.get();
        if (!onNetwork.getAsBoolean() || live.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        List<CompletableFuture<Optional<Map<String, String>>>> asked = otherServers.get().stream()
                .map(server -> live.get().ask(server, STATE, Map.of(), TIMEOUT)).toList();
        return CompletableFuture.allOf(asked.toArray(CompletableFuture[]::new)).thenRun(() -> {
            long newest = 0;
            State adopted = null;
            for (CompletableFuture<Optional<Map<String, String>>> answer : asked) {
                Map<String, String> reply = answer.join().orElse(null);
                try {
                    long at = reply == null ? 0 : Long.parseLong(reply.get("at"));
                    if (at > newest) {
                        adopted = new State(Boolean.parseBoolean(reply.get("frozen")),
                                Integer.parseInt(reply.get("slowmode")));
                        newest = at;
                    }
                } catch (NumberFormatException unreadable) {
                    // An answer that is not a state is no answer.
                }
            }
            synchronized (this) {
                if (adopted != null && newest > changedAt) {
                    changedAt = newest;
                    receiver.adopt(adopted);
                }
            }
        });
    }
}
