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
import java.util.function.BiPredicate;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * A poll across the servers of a network. The server it was started on keeps the count (Core's {@code Votes}, one
 * vote per player); the others show it and send each click there with {@link NetworkCalls}, so a player counts once
 * whichever server they click on. The start and the result go to every server on the bus.
 *
 * <p>Whoever a message names — who votes, who asks for the count or ends it, who started the poll — must be on the
 * server that sent it; the server that owns the poll is the only one whose result counts. No player named means the
 * console of the sending server.
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

        /** @param origin the server that sent the result */
        void result(String origin, UUID voteId, List<Integer> counts);
    }

    private final NetworkBus bus;
    private final Supplier<Optional<NetworkCalls>> calls;
    private final BooleanSupplier onNetwork;
    private final BiPredicate<UUID, String> playerOn;
    private final Rejected rejected = new Rejected();
    private final Host host;
    private final Guest guest;
    private final List<NetworkBus.Subscription> subscriptions = new ArrayList<>();

    public NetworkPolls(NetworkBus bus, Supplier<Optional<NetworkCalls>> calls, BooleanSupplier onNetwork,
                        BiPredicate<UUID, String> playerOn, Host host, Guest guest) {
        this.bus = bus;
        this.calls = calls;
        this.onNetwork = onNetwork;
        this.playerOn = playerOn;
        this.host = host;
        this.guest = guest;
    }

    public void listen() {
        if (!onNetwork.getAsBoolean()) {
            return;
        }
        subscriptions.add(bus.subscribe(TOPIC, this::heard));
        calls.get().ifPresent(live -> {
            subscriptions.add(answering(live, VOTE, (server, question) -> {
                UUID voter = UUID.fromString(question.get("voter"));
                if (!playerOn.test(voter, server)) {
                    rejected.of(server, VOTE);
                    return Map.of();
                }
                return host.vote(UUID.fromString(question.get("poll")), voter, question.get("answer"))
                        .map(name -> Map.of("ballot", name)).orElse(Map.of());
            }));
            subscriptions.add(answering(live, COUNTS, (server, question) ->
                    askedBy(server, COUNTS, question)
                            ? host.counts(UUID.fromString(question.get("poll"))).map(NetworkPolls::encode)
                                    .orElse(Map.of())
                            : Map.of()));
            subscriptions.add(answering(live, END, (server, question) ->
                    askedBy(server, END, question)
                            ? Map.of("ended", String.valueOf(host.end(UUID.fromString(question.get("poll")))))
                            : Map.of()));
        });
    }

    /** An answer that is empty when the question is garbled, instead of a failure in the log for every one. */
    private static NetworkBus.Subscription answering(NetworkCalls live, String topic,
                                                     BiFunction<String, Map<String, String>, Map<String, String>> answer) {
        return live.answerFrom(topic, (server, question) -> {
            try {
                return CompletableFuture.completedFuture(answer.apply(server, question));
            } catch (RuntimeException garbled) {
                return CompletableFuture.completedFuture(Map.of());
            }
        });
    }

    /** Whether the asking player is on the server that asked, or the asker is that server's console. */
    private boolean askedBy(String server, String topic, Map<String, String> question) {
        String asker = question.get("asker");
        if (asker == null ? "true".equals(question.get("console")) : playerOn.test(UUID.fromString(asker), server)) {
            return true;
        }
        rejected.of(server, topic);
        return false;
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

    private static Map<String, String> asking(UUID voteId, UUID asker) {
        return asker == null ? Map.of("poll", voteId.toString(), "console", "true")
                : Map.of("poll", voteId.toString(), "asker", asker.toString());
    }

    /** @param asker who asks; null for the console
     *  @return the votes per answer, in the poll's order; empty when the owning server did not answer */
    public CompletableFuture<Optional<List<Integer>>> counts(String origin, UUID voteId, UUID asker) {
        return ask(origin, COUNTS, asking(voteId, asker))
                .thenApply(reply -> reply.flatMap(NetworkPolls::decode));
    }

    /** @param asker who asks; null for the console
     *  @return whether the owning server ended it */
    public CompletableFuture<Boolean> end(String origin, UUID voteId, UUID asker) {
        return ask(origin, END, asking(voteId, asker))
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
                UUID starter = UUID.fromString(payload.get("starter").getAsString());
                if (!playerOn.test(starter, event.server())) {
                    rejected.of(event.server(), "poll open");
                    return;
                }
                guest.opened(event.server(), new Opened(voteId, payload.get("question").getAsString(),
                        List.copyOf(answers), starter,
                        payload.get("starterName").getAsString(), payload.get("lasting").getAsLong()));
            } else if (event.key().startsWith("result:")) {
                List<Integer> counts = new ArrayList<>();
                JsonParser.parseString(event.payload()).getAsJsonArray()
                        .forEach(count -> counts.add(count.getAsInt()));
                guest.result(event.server(), voteId, counts);
            }
        } catch (RuntimeException notAPoll) {
            // A malformed poll from another server is one nobody sees, not a broken chat.
        }
    }
}
