package de.raindancer.modules.chat.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.raindancer.core.platform.network.NetworkBus;
import de.raindancer.core.platform.network.NetworkCalls;
import de.raindancer.core.platform.network.NetworkEvent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * A poll across the servers of a network. The server it was started on keeps the count (Core's {@code Votes}, one
 * vote per player); the others show it and send each click there with {@link NetworkCalls}, so a player counts once
 * whichever server they click on. The start and the result go to every server on the bus.
 */
public final class NetworkPolls {

    public static final String TOPIC = "chat.poll";
    public static final String VOTE = "chat.poll.vote";
    public static final String COUNTS = "chat.poll.counts";
    public static final String END = "chat.poll.end";

    private static final Gson JSON = new Gson();
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** A poll that was just started, as the other servers are told of it. */
    public record Opened(UUID voteId, String question, List<String> answers, UUID starter, String starterName,
                         long lastingMillis) {
    }

    /** The server that owns the poll answers for it. */
    public interface Host {

        /** @return the name of the {@code Ballot}, empty when no such poll runs here */
        Optional<String> vote(UUID voteId, UUID voter, String answer);

        /** @return the votes per answer, in the poll's order; empty when no such poll runs here */
        Optional<List<Integer>> counts(UUID voteId);

        /** Ends the poll now. */
        boolean end(UUID voteId);
    }

    /** What a server that does not own the poll does when told of it. */
    public interface Guest {

        void opened(String origin, Opened poll);

        void result(UUID voteId, List<Integer> counts);
    }

    private final NetworkBus bus;
    private final Supplier<Optional<NetworkCalls>> calls;
    private final BooleanSupplier onNetwork;
    private final Host host;
    private final Guest guest;
    private final List<NetworkBus.Subscription> subscriptions = new ArrayList<>();

    public NetworkPolls(NetworkBus bus, Supplier<Optional<NetworkCalls>> calls, BooleanSupplier onNetwork,
                        Host host, Guest guest) {
        this.bus = bus;
        this.calls = calls;
        this.onNetwork = onNetwork;
        this.host = host;
        this.guest = guest;
    }

    public void listen() {
        if (!onNetwork.getAsBoolean()) {
            return;
        }
        subscriptions.add(bus.subscribe(TOPIC, this::heard));
        calls.get().ifPresent(live -> {
            subscriptions.add(live.answer(VOTE, question -> {
                Optional<String> ballot = host.vote(UUID.fromString(question.get("poll")),
                        UUID.fromString(question.get("voter")), question.get("answer"));
                return ballot.map(name -> Map.of("ballot", name)).orElse(Map.of());
            }));
            subscriptions.add(live.answer(COUNTS, question -> host.counts(UUID.fromString(question.get("poll")))
                    .map(NetworkPolls::encode).orElse(Map.of())));
            subscriptions.add(live.answer(END, question -> Map.of("ended",
                    String.valueOf(host.end(UUID.fromString(question.get("poll")))))));
        });
    }

    public void stop() {
        subscriptions.forEach(NetworkBus.Subscription::close);
        subscriptions.clear();
    }

    // ------------------------------------------------------------------ the owning server

    public void opened(Opened poll) {
        if (!onNetwork.getAsBoolean()) {
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("question", poll.question());
        JsonArray answers = new JsonArray();
        poll.answers().forEach(answers::add);
        payload.add("answers", answers);
        payload.addProperty("starter", poll.starter().toString());
        payload.addProperty("starterName", poll.starterName());
        payload.addProperty("lasting", poll.lastingMillis());
        bus.publish(TOPIC, "open:" + poll.voteId(), JSON.toJson(payload));
    }

    public void result(UUID voteId, List<Integer> counts) {
        if (onNetwork.getAsBoolean()) {
            bus.publish(TOPIC, "result:" + voteId, JSON.toJson(counts));
        }
    }

    // ------------------------------------------------------------------ the other servers

    /** @return the {@code Ballot}'s name, empty when the owning server did not answer */
    public CompletableFuture<Optional<String>> vote(String origin, UUID voteId, UUID voter, String answer) {
        return ask(origin, VOTE, Map.of("poll", voteId.toString(), "voter", voter.toString(), "answer", answer))
                .thenApply(reply -> reply.map(found -> found.get("ballot")));
    }

    /** @return the votes per answer, in the poll's order; empty when the owning server did not answer */
    public CompletableFuture<Optional<List<Integer>>> counts(String origin, UUID voteId) {
        return ask(origin, COUNTS, Map.of("poll", voteId.toString()))
                .thenApply(reply -> reply.flatMap(NetworkPolls::decode));
    }

    /** @return whether the owning server ended it */
    public CompletableFuture<Boolean> end(String origin, UUID voteId) {
        return ask(origin, END, Map.of("poll", voteId.toString()))
                .thenApply(reply -> reply.map(found -> "true".equals(found.get("ended"))).orElse(false));
    }

    private CompletableFuture<Optional<Map<String, String>>> ask(String origin, String topic, Map<String, String> data) {
        Optional<NetworkCalls> live = calls.get();
        if (live.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return live.get().ask(origin, topic, data, TIMEOUT).exceptionally(failed -> Optional.empty());
    }

    // ------------------------------------------------------------------ wire

    private static Map<String, String> encode(List<Integer> counts) {
        Map<String, String> reply = new LinkedHashMap<>();
        for (int at = 0; at < counts.size(); at++) {
            reply.put("c" + at, String.valueOf(counts.get(at)));
        }
        return reply;
    }

    private static Optional<List<Integer>> decode(Map<String, String> reply) {
        if (!reply.containsKey("c0")) {
            return Optional.empty();
        }
        List<Integer> counts = new ArrayList<>();
        try {
            for (int at = 0; reply.containsKey("c" + at); at++) {
                counts.add(Integer.parseInt(reply.get("c" + at)));
            }
        } catch (NumberFormatException unreadable) {
            return Optional.empty();
        }
        return Optional.of(counts);
    }

    private void heard(NetworkEvent event) {
        try {
            int colon = event.key().indexOf(':');
            UUID voteId = UUID.fromString(event.key().substring(colon + 1));
            if (event.key().startsWith("open:")) {
                JsonObject payload = JsonParser.parseString(event.payload()).getAsJsonObject();
                List<String> answers = new ArrayList<>();
                payload.getAsJsonArray("answers").forEach(answer -> answers.add(answer.getAsString()));
                guest.opened(event.server(), new Opened(voteId, payload.get("question").getAsString(),
                        List.copyOf(answers), UUID.fromString(payload.get("starter").getAsString()),
                        payload.get("starterName").getAsString(), payload.get("lasting").getAsLong()));
            } else if (event.key().startsWith("result:")) {
                List<Integer> counts = new ArrayList<>();
                JsonParser.parseString(event.payload()).getAsJsonArray()
                        .forEach(count -> counts.add(count.getAsInt()));
                guest.result(voteId, counts);
            }
        } catch (RuntimeException notAPoll) {
            // A malformed poll from another server is one nobody sees, not a broken chat.
        }
    }
}
