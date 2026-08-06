package de.raindancer.modules.hungergames.service;

import de.raindancer.modules.hungergames.HungerGamesSettings;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The HTTP API's routing table: patterns such as {@code /api/teams/{id}/members} mapped to handlers,
 * with path parameters extracted on a match.
 *
 * <p>Bukkit- and HTTP-free, and so entirely unit-testable — the transport (authentication, body parsing,
 * the main-thread hop, serialisation) lives in {@link HttpApiService}; the game logic lives in the
 * {@code *Endpoints} classes registered here.
 *
 * <p>Every method other than {@code GET} is classed as a write, and {@link HttpApiService} refuses every
 * write with {@code 403} while {@code api.read-only} is on — centrally, so an endpoint cannot forget to
 * check it. See {@link Route#write()}.
 */
public final class ApiRouter implements IHungerGamesService {

    /** A handler is given the parsed request and answers with a response. */
    @FunctionalInterface
    public interface Handler {
        ApiResponse handle(ApiRequest request) throws Exception;
    }

    /** One endpoint module's registrations. */
    @FunctionalInterface
    public interface Module {
        void register(ApiRouter router);
    }

    /**
     * One registered route.
     *
     * @param mainThread {@code true} when the handler touches Bukkit and must therefore run on the
     *                   server's main thread
     */
    public record Route(String method, String pattern, List<String> segments,
                         boolean write, boolean mainThread, String description, Handler handler) {
    }

    /** The outcome of resolving a path. */
    public record Match(Route route, Map<String, String> pathParams) {
    }

    private final List<Route> routes = new ArrayList<>();

    // ==================== registration ====================

    /** A read endpoint on the main thread (Bukkit access allowed). */
    public ApiRouter get(String pattern, String description, Handler handler) {
        return add("GET", pattern, true, description, handler);
    }

    /** A read endpoint with no main-thread hop — must not touch Bukkit. */
    public ApiRouter getAsync(String pattern, String description, Handler handler) {
        return add("GET", pattern, false, description, handler);
    }

    public ApiRouter post(String pattern, String description, Handler handler) {
        return add("POST", pattern, true, description, handler);
    }

    /** A write endpoint with no main-thread hop — must not touch Bukkit. */
    public ApiRouter postAsync(String pattern, String description, Handler handler) {
        return add("POST", pattern, false, description, handler);
    }

    public ApiRouter patch(String pattern, String description, Handler handler) {
        return add("PATCH", pattern, true, description, handler);
    }

    public ApiRouter put(String pattern, String description, Handler handler) {
        return add("PUT", pattern, true, description, handler);
    }

    public ApiRouter delete(String pattern, String description, Handler handler) {
        return add("DELETE", pattern, true, description, handler);
    }

    /** A write DELETE endpoint with no main-thread hop. */
    public ApiRouter deleteAsync(String pattern, String description, Handler handler) {
        return add("DELETE", pattern, false, description, handler);
    }

    public ApiRouter register(Module... modules) {
        for (Module module : modules) {
            module.register(this);
        }
        return this;
    }

    private ApiRouter add(String method, String pattern, boolean mainThread,
                           String description, Handler handler) {
        List<String> segments = split(pattern);
        boolean write = !method.equals("GET");
        Route route = new Route(method, pattern, segments, write, mainThread, description, handler);
        for (Route existing : routes) {
            if (existing.method().equals(method) && existing.pattern().equals(pattern)) {
                throw new IllegalStateException("Route registered twice: " + method + " " + pattern);
            }
        }
        routes.add(route);
        return this;
    }

    // ==================== resolution ====================

    /**
     * Finds the matching route. Where several candidates fit, the one with the most literal (non-
     * parameterised) segments wins, so {@code /api/teams/random} is chosen over {@code /api/teams/{id}}.
     */
    public Optional<Match> match(String method, String path) {
        List<String> actual = split(path);
        return routes.stream()
                .filter(route -> route.method().equalsIgnoreCase(method))
                .filter(route -> matches(route, actual))
                .max(Comparator.comparingInt(ApiRouter::literalCount))
                .map(route -> new Match(route, extract(route, actual)));
    }

    /** Every method registered for this path (for a {@code 405}). */
    public Set<String> methodsFor(String path) {
        List<String> actual = split(path);
        Set<String> methods = new LinkedHashSet<>();
        for (Route route : routes) {
            if (matches(route, actual)) {
                methods.add(route.method());
            }
        }
        return methods;
    }

    /** Every route, in registration order — what {@code GET /api} lists. */
    public List<Route> routes() {
        return List.copyOf(routes);
    }

    @Override
    public void settings(HungerGamesSettings settings) {
        // The routing table does not vary with settings — a route exists or it does not, whatever
        // api.read-only or api.port happen to be right now. Declared anyway, empty, because a router
        // that started reading a setting without anybody remembering this method exists is exactly the
        // failure IHungerGamesService is here to rule out.
    }

    // ==================== internal ====================

    private static boolean matches(Route route, List<String> actual) {
        if (route.segments().size() != actual.size()) {
            return false;
        }
        for (int i = 0; i < actual.size(); i++) {
            String expected = route.segments().get(i);
            if (isParam(expected)) {
                if (actual.get(i).isEmpty()) {
                    return false;
                }
            } else if (!expected.equalsIgnoreCase(actual.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, String> extract(Route route, List<String> actual) {
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i < actual.size(); i++) {
            String expected = route.segments().get(i);
            if (isParam(expected)) {
                params.put(expected.substring(1, expected.length() - 1), actual.get(i));
            }
        }
        return params;
    }

    private static int literalCount(Route route) {
        int count = 0;
        for (String segment : route.segments()) {
            if (!isParam(segment)) {
                count++;
            }
        }
        return count;
    }

    private static boolean isParam(String segment) {
        return segment.startsWith("{") && segment.endsWith("}");
    }

    /** Splits a path into decoded segments; leading and repeated slashes vanish. */
    static List<String> split(String path) {
        List<String> segments = new ArrayList<>();
        for (String raw : path.split("/")) {
            if (!raw.isEmpty()) {
                segments.add(URLDecoder.decode(raw, StandardCharsets.UTF_8));
            }
        }
        return segments;
    }

    /** Normalises an HTTP method. */
    static String normalizeMethod(String method) {
        return method == null ? "" : method.toUpperCase(Locale.ROOT);
    }
}
