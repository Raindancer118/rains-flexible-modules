package de.raindancer.modules.hungergames.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * The HTTP API's transport: the socket, the key, the read-only lock, the body, the thread hop and the
 * bytes on the wire. What the endpoints <em>are</em> lives in the {@code *Endpoints} classes and the
 * mapping is {@link ApiRouter}'s; nothing about a route is decided here.
 *
 * <p>That split is the reason every endpoint in this package is testable with a plain method call. This
 * class is the only thing in it that needs a socket, and it is deliberately the only thing that has no
 * opinion about the game.
 *
 * <h2>What this API is for, and what it is not</h2>
 * A stream overlay, a tournament dashboard, a regie desk in the next room. It is the JDK's own
 * {@code HttpServer}, so nothing is added to the jar for it.
 *
 * <p><b>There is no TLS and there never will be here.</b> It belongs behind a closed network or a reverse
 * proxy and never on the open internet. Off by default and bound to {@code 127.0.0.1} by default, which
 * together mean that reaching it from another machine is two deliberate decisions rather than an
 * oversight. Every request but {@code /api/health} needs the configured key in {@code X-API-Key} (or as a
 * {@code Bearer} token), compared in constant time; if none is configured, one is generated at startup
 * and written back to the config, because a server that came up with an empty key would either let
 * nobody in or — depending on how the comparison was written — everybody.
 *
 * <h2>Threads, and why the hop is injected</h2>
 * The HTTP server runs on its own daemon threads. A handler that touches the world, a player or an
 * inventory cannot run there, so routes marked {@code mainThread} are hopped onto the server thread and
 * waited for, with a timeout: a request that would otherwise hang holds an HTTP thread and, worse, holds
 * whatever it locked.
 *
 * <p>The hop arrives as {@link ServerThread} rather than being called directly, for two reasons. The
 * first is Folia, where "the main thread" is not a thing and the work goes to the global region
 * scheduler — {@link #viaScheduling} is the real implementation and Core's {@code Scheduling} is what
 * knows the difference. The second is that a test can pass {@link #inline()} and exercise every branch of
 * {@link #dispatch} — authentication, the read-only lock, a handler that throws, a handler that times
 * out — without a server anywhere near it.
 */
public final class HttpApiService implements IHungerGamesService {

    /**
     * How long a request may wait for the server thread before it is answered with 503.
     *
     * <p>Five seconds. Long enough to survive a busy tick, short enough that a stuck request does not sit
     * on an HTTP thread until somebody restarts the server. The caller gets a "server busy" it can retry
     * rather than a connection that never answers.
     */
    public static final Duration SERVER_THREAD_TIMEOUT = Duration.ofSeconds(5);

    /**
     * The most JSON this will read from a request.
     *
     * <p>64 KiB, which is far more than any endpoint here needs and far less than a body somebody could
     * use to make the server allocate until it stops. The limit is enforced by reading no more than this
     * and then checking whether there was more — not by trusting {@code Content-Length}, which the caller
     * writes.
     */
    public static final int MAX_BODY_BYTES = 64 * 1024;

    private static final Gson GSON = new Gson();

    /** Running a piece of work where the server's own state may be touched, and waiting for the answer. */
    @FunctionalInterface
    public interface ServerThread {
        <T> T call(Callable<T> work) throws Exception;
    }

    private final Plugin plugin;
    private final ApiSupport support;
    private final LogChannel log;
    private final ApiRouter router;
    private final ServerThread serverThread;

    /**
     * Persists a generated key. Handed in rather than reached for, because writing a setting is the one
     * thing this class does that changes the server's configuration, and a test must be able to watch it
     * happen without a config file.
     */
    private final Consumer<String> rememberKey;

    private HungerGamesSettings settings;

    private HttpServer server;
    private ExecutorService executor;

    /**
     * @param router       already carrying every endpoint module — see {@link #route(ApiSupport)}
     * @param rememberKey  called with a freshly generated key so it reaches {@code config.yml}
     */
    public HttpApiService(Plugin plugin, ApiSupport support, LogChannel log, ApiRouter router,
                          ServerThread serverThread, Consumer<String> rememberKey,
                          HungerGamesSettings settings) {
        this.plugin = plugin;
        this.support = support;
        this.log = log;
        this.router = router;
        this.serverThread = serverThread;
        this.rememberKey = rememberKey;
        this.settings = settings;
    }

    /**
     * Everything the endpoints reach into the running game through.
     *
     * <p>Every one of these is an interface declared by the endpoint class that needs it, stating exactly
     * what the HTTP layer wants and nothing more — {@code GameEndpoints.GameControl} is eighteen methods
     * about a round, not a handle on {@link GameControlService}. That is what lets a test hand an endpoint
     * a two-line fake instead of a running tournament, and what stops the API growing a private door into
     * a service that the commands and screens do not have.
     *
     * <p>A record because it is exactly a bundle of collaborators with no behaviour, and because a
     * positional constructor with eight arguments of eight different interface types cannot have two of
     * them swapped by accident.
     */
    public record Wiring(
            GameEndpoints.GameControl control,
            EventEndpoints.Deathmatch deathmatch,
            EventEndpoints.SupplyDrops supplyDrops,
            EventEndpoints.Sponsors sponsors,
            EventEndpoints.MonsterWaves monsterWaves,
            EventEndpoints.SoundEffects soundEffects,
            LootEndpoints.Catalogue loot,
            AdminEndpoints.Gamemasters gamemasters,
            AdminEndpoints.Spectator spectator,
            AdminEndpoints.Simulation simulation,
            SettingsStore<HungerGamesSettings> settingsStore) {
    }

    /**
     * The router with every endpoint module on it.
     *
     * <p>Static and separate from the constructor so that {@code HttpApiRouteTableTest} can build the
     * whole routing table — and check it for duplicate paths, missing descriptions and write routes that
     * forgot to say so — without constructing a service or opening a socket.
     */
    public static ApiRouter route(ApiSupport support, Wiring wiring) {
        ApiRouter router = new ApiRouter();
        router.register(
                new StatusEndpoints(support),
                new TeamEndpoints(support),
                new GameEndpoints(support, wiring.control()),
                new EventEndpoints(support, wiring.deathmatch(), wiring.supplyDrops(), wiring.sponsors(),
                        wiring.monsterWaves(), wiring.soundEffects()),
                new ConfigEndpoints(support, wiring.settingsStore()),
                new LootEndpoints(support, wiring.loot()),
                new AdminEndpoints(support, wiring.gamemasters(), wiring.spectator(),
                        wiring.simulation()));
        return router;
    }

    // ==================== the thread hop ====================

    /**
     * The real hop: onto the global region scheduler, waited for with a timeout.
     *
     * <p>Core's {@code Scheduling.global} is what knows whether this server is Folia. Deliberately not
     * {@code Bukkit.getScheduler().callSyncMethod}, which does not exist on Folia at all — the version
     * this replaces used it, so the API would have taken the whole plugin down on the first request to a
     * world-touching endpoint on a Folia server.
     *
     * <p>Never call this from the server thread: it schedules work for a later tick and then blocks
     * waiting for it, which on the thread that would run it is a deadlock. It cannot happen through the
     * HTTP path — those requests always arrive on an HTTP thread — and it is why the hop is an interface
     * rather than something helpful that tries to detect the case and gets it wrong on Folia.
     */
    public static ServerThread viaScheduling(Plugin plugin, Duration timeout) {
        return new ServerThread() {
            @Override
            public <T> T call(Callable<T> work) throws Exception {
                CompletableFuture<T> answer = new CompletableFuture<>();
                Scheduling.global(plugin, () -> {
                    try {
                        answer.complete(work.call());
                    } catch (Throwable thrown) {
                        answer.completeExceptionally(thrown);
                    }
                });
                try {
                    return answer.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                } catch (ExecutionException failed) {
                    throw asException(failed.getCause());
                }
            }
        };
    }

    /** Runs the work where it stands. For tests, and for nothing else. */
    public static ServerThread inline() {
        return new ServerThread() {
            @Override
            public <T> T call(Callable<T> work) throws Exception {
                return work.call();
            }
        };
    }

    private static Exception asException(Throwable thrown) {
        if (thrown instanceof Exception exception) {
            return exception;
        }
        return new IllegalStateException(thrown);
    }

    // ==================== lifecycle ====================

    /**
     * Opens the socket, if {@code api.enabled} says so.
     *
     * <p>A port already in use is logged and nothing else: the API not coming up is a dashboard that does
     * not connect, and taking the tournament down over it would be the wrong trade by a wide margin.
     */
    public void start() {
        if (!settings.apiEnabled()) {
            return;
        }
        ensureThereIsAKey();

        String bind = settings.apiBindAddress();
        int port = settings.apiPort();
        try {
            server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        } catch (IOException | IllegalArgumentException refused) {
            log.warn("The HTTP API could not start on {}:{} ({}). The round is unaffected; anything "
                    + "reading it — a dashboard, an overlay — will not connect.",
                    bind, port, refused.getMessage());
            server = null;
            return;
        }

        executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "HungerGames-HttpApi");
            // Daemon, so a server shutting down is never held open by an idle HTTP thread.
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/api", this::handleSafely);
        server.start();

        if (isLocalOnly(bind)) {
            log.info("The HTTP API is on {}:{} with {} endpoints, reachable only from this machine.",
                    bind, port, router.routes().size());
        } else {
            log.warn("The HTTP API is on {}:{} with {} endpoints and is NOT restricted to this machine. "
                    + "There is no TLS: expose it only inside a closed network or behind a reverse proxy.",
                    bind, port, router.routes().size());
        }
    }

    /** Whether that bind address can only be reached from the machine the server runs on. */
    private static boolean isLocalOnly(String bind) {
        return bind != null && (bind.equals("127.0.0.1") || bind.equals("::1")
                || bind.equalsIgnoreCase("localhost"));
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    public boolean isRunning() {
        return server != null;
    }

    /** The routing table, for tests and for generating documentation. */
    public ApiRouter router() {
        return router;
    }

    /**
     * A reload changes what the next request is checked against — the key, the read-only lock, the
     * addresses. It does not move a listening socket: an API that silently rebound itself mid-tournament
     * would drop every connected dashboard, and the operator who changed the port is better told to
     * restart than surprised.
     */
    @Override
    public void settings(HungerGamesSettings settings) {
        this.settings = settings;
    }

    /** Generates and persists a key when none is configured. */
    void ensureThereIsAKey() {
        if (settings.apiKey() != null && !settings.apiKey().isBlank()) {
            return;
        }
        String key = ApiKeys.generate();
        rememberKey.accept(key);
        // Deliberately not logged. A key in the console is a key in every log aggregator the server
        // ships to, and in the screenshot somebody posts when they ask why the API will not start.
        log.info("The HTTP API had no key, so one was generated and written to the config "
                + "(api.key) — read it there, or on the HTTP API settings page.");
    }

    // ==================== transport ====================

    private void handleSafely(HttpExchange exchange) throws IOException {
        try {
            handle(exchange);
        } catch (Exception unhandled) {
            log.warn("The HTTP API hit an unhandled error on {}: {}",
                    exchange.getRequestURI(), unhandled.toString());
            respond(exchange, ApiResponse.error(500, "Internal error"));
        } finally {
            exchange.close();
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        var headers = exchange.getResponseHeaders();
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Headers", "X-API-Key, Authorization, Content-Type");
        headers.set("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS");

        String method = ApiRouter.normalizeMethod(exchange.getRequestMethod());
        String path = exchange.getRequestURI().getPath();

        if (method.equals("OPTIONS")) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        // Before the key check on purpose: something has to be able to say "the API is up" without
        // holding a credential, or a health probe becomes a place to keep one.
        if (path.equals("/api/health")) {
            respond(exchange, ApiResponse.json(health()));
            return;
        }
        if (!authorized(exchange)) {
            respond(exchange, ApiResponse.error(401, "Missing or invalid X-API-Key"));
            return;
        }
        if (path.equals("/api")) {
            respond(exchange, index());
            return;
        }

        Optional<ApiRouter.Match> match = router.match(method, path);
        if (match.isEmpty()) {
            respond(exchange, notMatched(method, path));
            return;
        }

        ApiRouter.Route route = match.get().route();
        if (route.write() && settings.apiReadOnly()) {
            respond(exchange, ApiResponse.error(403, "The API is read-only (api.read-only)"));
            return;
        }

        ApiRequest request;
        try {
            request = new ApiRequest(method, path, match.get().pathParams(),
                    ApiRequest.parseQuery(exchange.getRequestURI().getRawQuery()),
                    readBody(exchange));
        } catch (ApiBadRequestException malformed) {
            respond(exchange, ApiResponse.badRequest(malformed.getMessage()));
            return;
        }
        respond(exchange, dispatch(route, request));
    }

    /**
     * Runs a handler — on the server thread when the route says so — and turns whatever comes back out
     * of it into a status code.
     *
     * <p>Package-private rather than private so the tests can drive every branch directly: this is where
     * an endpoint throwing turns into a 500 rather than into a dead connection, and that is worth
     * checking without a socket.
     */
    ApiResponse dispatch(ApiRouter.Route route, ApiRequest request) {
        try {
            if (!route.mainThread()) {
                return route.handler().handle(request);
            }
            return serverThread.call(() -> route.handler().handle(request));
        } catch (ApiBadRequestException wrong) {
            return ApiResponse.badRequest(wrong.getMessage());
        } catch (ApiConflictException refused) {
            return ApiResponse.conflict(refused.getMessage());
        } catch (TimeoutException busy) {
            return ApiResponse.error(503, "The server did not answer in time (busy)");
        } catch (Exception broken) {
            // The class name and not the message: an exception's message is written for whoever reads
            // the log and can name a file path, a player or a table. The console gets all of it.
            log.warn("The HTTP API hit an error in {} {}: {}",
                    route.method(), route.pattern(), broken.toString());
            return ApiResponse.error(500, "Internal error: " + broken.getClass().getSimpleName());
        }
    }

    /**
     * Whether the request carries the configured key.
     *
     * <p>Accepts it as {@code X-API-Key} or as a {@code Bearer} token, because half of what people point
     * at an API sends one and half sends the other. The comparison is {@link ApiKeys#matches}, which is
     * constant-time — a comparison that returns as soon as two characters differ tells whoever is trying
     * how much of the key they have right.
     */
    private boolean authorized(HttpExchange exchange) {
        String provided = exchange.getRequestHeaders().getFirst("X-API-Key");
        if (provided == null) {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization != null && authorization.startsWith("Bearer ")) {
                provided = authorization.substring("Bearer ".length());
            }
        }
        return ApiKeys.matches(settings.apiKey(), provided);
    }

    /** 405 with an {@code Allow} hint when the path exists but not for that method. */
    private ApiResponse notMatched(String method, String path) {
        Set<String> allowed = router.methodsFor(path);
        if (allowed.isEmpty()) {
            return ApiResponse.notFound("Unknown endpoint: " + method + " " + path
                    + " — GET /api lists every endpoint");
        }
        return ApiResponse.error(405, method + " is not allowed on " + path
                + " (allowed: " + String.join(", ", allowed) + ")");
    }

    private JsonObject health() {
        JsonObject json = new JsonObject();
        json.addProperty("status", "ok");
        json.addProperty("endpoints", router.routes().size());
        json.addProperty("readOnly", settings.apiReadOnly());
        // Deliberately nothing about the round. This answers without a key, so everything in it is
        // public — how many tributes are alive is not.
        return json;
    }

    /** The self-description: every route, with its method, path and what it is for. */
    ApiResponse index() {
        JsonObject json = new JsonObject();
        JsonArray endpoints = new JsonArray();
        for (ApiRouter.Route route : router.routes()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("method", route.method());
            entry.addProperty("path", route.pattern());
            entry.addProperty("description", route.description());
            entry.addProperty("write", route.write());
            endpoints.add(entry);
        }
        json.add("endpoints", endpoints);
        json.addProperty("count", endpoints.size());
        json.addProperty("readOnly", settings.apiReadOnly());
        return ApiResponse.json(json);
    }

    /**
     * Reads the JSON body.
     *
     * @return the object, or {@code null} when no body was sent
     * @throws ApiBadRequestException on invalid JSON, on a body that is not an object, or on one over
     *                                {@link #MAX_BODY_BYTES}
     */
    private JsonObject readBody(HttpExchange exchange) throws IOException {
        InputStream body = exchange.getRequestBody();
        byte[] raw = body.readNBytes(MAX_BODY_BYTES);
        if (raw.length == 0) {
            return null;
        }
        // Read the limit exactly, then look for one more byte. Trusting Content-Length would be
        // trusting the caller about the thing the limit exists to defend against.
        if (raw.length == MAX_BODY_BYTES && body.read() != -1) {
            throw new ApiBadRequestException("The body is larger than " + MAX_BODY_BYTES + " bytes");
        }
        String text = new String(raw, StandardCharsets.UTF_8);
        if (text.isBlank()) {
            return null;
        }
        try {
            var element = JsonParser.parseString(text);
            if (!element.isJsonObject()) {
                throw new ApiBadRequestException("The body has to be a JSON object");
            }
            return element.getAsJsonObject();
        } catch (JsonParseException malformed) {
            throw new ApiBadRequestException("The body is not valid JSON");
        }
    }

    private void respond(HttpExchange exchange, ApiResponse response) throws IOException {
        byte[] bytes = GSON.toJson(response.body()).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(response.status(), bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** The plugin this belongs to, for a host wanting to name it. */
    public Plugin plugin() {
        return plugin;
    }

    /** The support object every endpoint shares, for a host wanting to add one of its own. */
    public ApiSupport support() {
        return support;
    }

    @Override
    public String describe() {
        return "the HTTP API's socket, key and thread hop";
    }
}
