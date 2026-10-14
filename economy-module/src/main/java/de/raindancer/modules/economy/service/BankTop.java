package de.raindancer.modules.economy.service;

import de.raindancer.core.platform.network.NetworkBus;
import de.raindancer.core.platform.network.NetworkCalls;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.Account;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** The richest list as one call across the network: the bank answers from its ranking, other servers show it. */
public final class BankTop {

    public static final String TOPIC = "economy:top";

    static final int SHOWN = 10;

    public record Entry(UUID id, String name, Money balance) {
    }

    /** The first places, the asker's own place (0 for none), or {@code off} when the bank switched the list off. */
    public record Top(boolean off, List<Entry> entries, int place) {
    }

    private BankTop() {
    }

    /**
     * Answers {@link #TOPIC} from {@code leaderboard} while {@code isBank}: a server that lost the role a moment ago
     * must not rank for callers who looked it up before. The ranking is read on {@code readOn} — the server thread.
     */
    public static NetworkBus.Subscription serve(NetworkCalls calls, LeaderboardService leaderboard,
                                                BooleanSupplier isBank, BooleanSupplier enabled, Executor readOn) {
        return calls.answerLater(TOPIC, data -> {
            if (!isBank.getAsBoolean()) {
                return CompletableFuture.completedFuture(Map.of());
            }
            return CompletableFuture.supplyAsync(() -> encode(leaderboard, data.get("player"), enabled.getAsBoolean()),
                            readOn)
                    .orTimeout(5, TimeUnit.SECONDS).exceptionally(slow -> Map.of());
        });
    }

    private static Map<String, String> encode(LeaderboardService leaderboard, String asker, boolean enabled) {
        Map<String, String> answer = new HashMap<>();
        if (!enabled) {
            answer.put("off", "true");
            return answer;
        }
        List<Account> ranking = leaderboard.ranking();
        int count = Math.min(SHOWN, ranking.size());
        answer.put("count", String.valueOf(count));
        for (int i = 0; i < count; i++) {
            answer.put("id." + i, ranking.get(i).id().toString());
            answer.put("name." + i, ranking.get(i).name());
            answer.put("balance." + i, String.valueOf(ranking.get(i).balance().minor()));
        }
        try {
            answer.put("place", String.valueOf(asker == null ? 0 : leaderboard.placeOf(UUID.fromString(asker))));
        } catch (IllegalArgumentException garbled) {
            answer.put("place", "0");
        }
        return answer;
    }

    /** What {@link #serve} sent; empty for an empty or garbled answer. */
    static Optional<Top> decode(Map<String, String> answer) {
        if ("true".equals(answer.get("off"))) {
            return Optional.of(new Top(true, List.of(), 0));
        }
        if (!answer.containsKey("count")) {
            return Optional.empty();
        }
        try {
            List<Entry> entries = new ArrayList<>();
            for (int i = 0; i < Integer.parseInt(answer.get("count")); i++) {
                entries.add(new Entry(UUID.fromString(answer.get("id." + i)), answer.get("name." + i),
                        Money.of(Long.parseLong(answer.get("balance." + i)))));
            }
            return Optional.of(new Top(false, List.copyOf(entries), Integer.parseInt(answer.getOrDefault("place", "0"))));
        } catch (RuntimeException garbled) {
            return Optional.empty();
        }
    }
}
