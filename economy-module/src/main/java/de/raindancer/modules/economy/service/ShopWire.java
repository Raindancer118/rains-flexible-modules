package de.raindancer.modules.economy.service;

import de.raindancer.core.platform.network.NetworkBus;
import de.raindancer.core.platform.network.NetworkCalls;

import java.time.Duration;
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
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The bank's shop over the network's calls: {@link #serve} answers on the bank from its {@link ShopDesk}; a
 * {@link Client} on any other server asks. Every answer is flat strings, as calls carry them.
 */
public final class ShopWire {

    public static final String CATALOGUE = "economy:shop-catalogue";
    public static final String QUOTE = "economy:shop-quote";
    public static final String BUY = "economy:shop-buy";
    public static final String SELL = "economy:shop-sell";

    /** Field and record separators no item name, money rendering or message value contains. */
    private static final String FIELD = "\u001f";
    private static final String RECORD = "\u001e";
    private static final long ANSWER_SECONDS = 5;

    private ShopWire() {
    }

    /**
     * Answers the shop's topics from {@code desk} while {@code isBank}, each on {@code onServer} — the shop touches
     * the market and the ledger the way the bank's own menus do, from the server thread.
     */
    public static List<NetworkBus.Subscription> serve(NetworkCalls calls, ShopDesk desk, BooleanSupplier isBank,
                                                      Executor onServer) {
        List<NetworkBus.Subscription> answering = new ArrayList<>();
        answering.add(calls.answerLater(CATALOGUE, data -> answer(isBank, onServer, () -> {
            Map<String, String> out = new HashMap<>();
            out.put("lines", desk.catalogue(UUID.fromString(data.get("player"))).stream()
                    .map(ShopWire::line).collect(Collectors.joining(RECORD)));
            return out;
        })));
        answering.add(calls.answerLater(QUOTE, data -> answer(isBank, onServer, () -> {
            ShopDesk.Quote quote = desk.quote(UUID.fromString(data.get("player")), data.get("material"),
                    counts(data.get("buy")), counts(data.get("sell")));
            Map<String, String> out = new HashMap<>();
            out.put("buyable", String.valueOf(quote.buyable()));
            out.put("sellable", String.valueOf(quote.sellable()));
            out.put("buys", prices(quote.buys()));
            out.put("sells", prices(quote.sells()));
            out.put("notes", String.join(RECORD, quote.notes()));
            return out;
        })));
        answering.add(calls.answerLater(BUY, data -> answer(isBank, onServer, () -> settled(desk.buy(
                UUID.fromString(data.get("player")), data.get("material"), Integer.parseInt(data.get("count")),
                Long.parseLong(data.get("most")))))));
        answering.add(calls.answerLater(SELL, data -> answer(isBank, onServer, () -> settled(desk.sell(
                UUID.fromString(data.get("player")), data.get("material"), Integer.parseInt(data.get("count")))))));
        return answering;
    }

    private static CompletableFuture<Map<String, String>> answer(BooleanSupplier isBank, Executor onServer,
                                                                 Supplier<Map<String, String>> work) {
        // A server that lost the role a moment ago must not book for callers who looked it up before.
        if (!isBank.getAsBoolean()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        return CompletableFuture.supplyAsync(work, onServer).orTimeout(ANSWER_SECONDS, TimeUnit.SECONDS)
                .exceptionally(failed -> Map.of());
    }

    // ------------------------------------------------------------------ encoding

    private static String line(ShopDesk.Line line) {
        return String.join(FIELD, line.material(), line.drawer(), String.valueOf(line.buyable()),
                String.valueOf(line.sellable()), line.buy(), line.sell(), String.valueOf(line.trend()));
    }

    private static ShopDesk.Line line(String encoded) {
        String[] parts = encoded.split(FIELD, -1);
        return new ShopDesk.Line(parts[0], parts[1], Boolean.parseBoolean(parts[2]), Boolean.parseBoolean(parts[3]),
                parts[4], parts[5], Integer.parseInt(parts[6]));
    }

    private static String prices(List<ShopDesk.Price> prices) {
        return prices.stream().map(price -> price.count() + FIELD + price.shown() + FIELD + price.minor())
                .collect(Collectors.joining(RECORD));
    }

    private static List<ShopDesk.Price> prices(String encoded) {
        List<ShopDesk.Price> read = new ArrayList<>();
        if (encoded == null || encoded.isEmpty()) {
            return read;
        }
        for (String one : encoded.split(RECORD, -1)) {
            String[] parts = one.split(FIELD, -1);
            read.add(new ShopDesk.Price(Integer.parseInt(parts[0]), parts[1], Long.parseLong(parts[2])));
        }
        return read;
    }

    private static List<Integer> counts(String encoded) {
        List<Integer> read = new ArrayList<>();
        if (encoded == null || encoded.isBlank()) {
            return read;
        }
        for (String one : encoded.split(",")) {
            read.add(Math.max(1, Integer.parseInt(one.strip())));
        }
        return read;
    }

    private static Map<String, String> settled(ShopDesk.Settled settled) {
        Map<String, String> out = new HashMap<>();
        out.put("ok", String.valueOf(settled.ok()));
        out.put("key", settled.key());
        out.put("values", String.join(RECORD, settled.values()));
        return out;
    }

    private static Optional<ShopDesk.Settled> settled(Map<String, String> answer) {
        if (!answer.containsKey("key")) {
            return Optional.empty();
        }
        String values = answer.getOrDefault("values", "");
        return Optional.of(new ShopDesk.Settled("true".equals(answer.get("ok")), answer.get("key"),
                values.isEmpty() ? List.of() : List.of(values.split(RECORD, -1))));
    }

    // ------------------------------------------------------------------ asking

    /** A server that is not the bank, asking it. Every answer is empty when the bank cannot be reached. */
    public static final class Client {

        private final NetworkCalls calls;
        private final Supplier<Optional<String>> bank;
        private final Duration wait;

        /** @param bank the bank's server id, when it is another server */
        public Client(NetworkCalls calls, Supplier<Optional<String>> bank, Duration wait) {
            this.calls = calls;
            this.bank = bank;
            this.wait = wait;
        }

        public CompletableFuture<Optional<List<ShopDesk.Line>>> catalogue(UUID player) {
            return ask(CATALOGUE, Map.of("player", player.toString())).thenApply(answer -> answer
                    .filter(said -> said.containsKey("lines")).map(said -> {
                        String lines = said.get("lines");
                        List<ShopDesk.Line> read = new ArrayList<>();
                        if (!lines.isEmpty()) {
                            for (String one : lines.split(RECORD, -1)) {
                                read.add(line(one));
                            }
                        }
                        return List.copyOf(read);
                    }));
        }

        public CompletableFuture<Optional<ShopDesk.Quote>> quote(UUID player, String material, List<Integer> buy,
                                                                 List<Integer> sell) {
            return ask(QUOTE, Map.of("player", player.toString(), "material", material,
                    "buy", buy.stream().map(String::valueOf).collect(Collectors.joining(",")),
                    "sell", sell.stream().map(String::valueOf).collect(Collectors.joining(","))))
                    .thenApply(answer -> answer.filter(said -> said.containsKey("buyable")).map(said -> {
                        String notes = said.getOrDefault("notes", "");
                        return new ShopDesk.Quote("true".equals(said.get("buyable")), "true".equals(said.get("sellable")),
                                prices(said.get("buys")), prices(said.get("sells")),
                                notes.isEmpty() ? List.of() : List.of(notes.split(RECORD, -1)));
                    }));
        }

        public CompletableFuture<Optional<ShopDesk.Settled>> buy(UUID player, String material, int count, long most) {
            return ask(BUY, Map.of("player", player.toString(), "material", material, "count", String.valueOf(count),
                    "most", String.valueOf(most))).thenApply(answer -> answer.flatMap(ShopWire::settled));
        }

        public CompletableFuture<Optional<ShopDesk.Settled>> sell(UUID player, String material, int count) {
            return ask(SELL, Map.of("player", player.toString(), "material", material, "count", String.valueOf(count)))
                    .thenApply(answer -> answer.flatMap(ShopWire::settled));
        }

        private CompletableFuture<Optional<Map<String, String>>> ask(String topic, Map<String, String> data) {
            Optional<String> server = bank.get();
            if (server.isEmpty()) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            return calls.ask(server.get(), topic, data, wait).exceptionally(failed -> Optional.empty())
                    .thenApply(answer -> {
                        try {
                            return answer.filter(said -> !said.isEmpty());
                        } catch (RuntimeException garbled) {
                            return Optional.empty();
                        }
                    });
        }
    }
}
