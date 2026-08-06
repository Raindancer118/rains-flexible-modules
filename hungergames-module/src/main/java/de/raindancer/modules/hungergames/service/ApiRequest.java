package de.raindancer.modules.hungergames.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * One parsed API request: method, path, the path parameters a route's pattern captured, the query
 * string, and the JSON body.
 *
 * <p>The {@code require*} helpers throw {@link ApiBadRequestException} when a field is missing or will
 * not parse, so a handler unpacks its parameters in one line each rather than growing a validation branch
 * per field. Bukkit-free, and so testable with nothing but a body string.
 *
 * <p>Not a service: a fresh instance is built for every request and none of it survives past that one
 * call, so there is nothing here a settings reload could leave stale.
 */
public final class ApiRequest {

    private final String method;
    private final String path;
    private final Map<String, String> pathParams;
    private final Map<String, String> query;
    private final JsonObject body;

    public ApiRequest(String method, String path, Map<String, String> pathParams,
                       Map<String, String> query, JsonObject body) {
        this.method = method;
        this.path = path;
        this.pathParams = Map.copyOf(pathParams);
        this.query = Map.copyOf(query);
        this.body = body;
    }

    public String method() {
        return method;
    }

    public String path() {
        return path;
    }

    /** The path parameter a route's pattern captured, e.g. {@code {id}}. */
    public String param(String name) {
        String value = pathParams.get(name);
        if (value == null) {
            throw new IllegalStateException("This route has no path parameter named \"" + name + "\"");
        }
        return value;
    }

    /** The JSON body, or {@code null} when none was sent. */
    public JsonObject body() {
        return body;
    }

    public boolean has(String field) {
        return body != null && body.has(field) && !body.get(field).isJsonNull();
    }

    // ==================== body fields ====================

    public String requireString(String field) {
        String value = optString(field, null);
        if (value == null || value.isBlank()) {
            throw new ApiBadRequestException("Field \"" + field + "\" is missing from the JSON body");
        }
        return value;
    }

    public String optString(String field, String fallback) {
        if (!has(field)) {
            return fallback;
        }
        return body.get(field).getAsString().trim();
    }

    public int requireInt(String field) {
        return asInt(field, require(field));
    }

    public int optInt(String field, int fallback) {
        return has(field) ? asInt(field, body.get(field)) : fallback;
    }

    public double requireDouble(String field) {
        return asDouble(field, require(field));
    }

    public double optDouble(String field, double fallback) {
        return has(field) ? asDouble(field, body.get(field)) : fallback;
    }

    public boolean optBool(String field, boolean fallback) {
        if (!has(field)) {
            return fallback;
        }
        JsonElement element = body.get(field);
        try {
            return element.getAsBoolean();
        } catch (RuntimeException e) {
            throw new ApiBadRequestException("Field \"" + field + "\" must be true or false");
        }
    }

    public UUID requireUuid(String field) {
        return parseUuid(requireString(field));
    }

    public Optional<UUID> optUuid(String field) {
        return has(field) ? Optional.of(requireUuid(field)) : Optional.empty();
    }

    /** An enum value from the body, case-insensitive. */
    public <E extends Enum<E>> E requireEnum(String field, Class<E> type) {
        return parseEnum(field, requireString(field), type);
    }

    public <E extends Enum<E>> E optEnum(String field, Class<E> type, E fallback) {
        return has(field) ? parseEnum(field, requireString(field), type) : fallback;
    }

    // ==================== query parameters ====================

    public String queryString(String name, String fallback) {
        String value = query.get(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    public int queryInt(String name, int fallback) {
        String raw = query.get(name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new ApiBadRequestException("Query parameter \"" + name + "\" is not a number: " + raw);
        }
    }

    public boolean queryBool(String name, boolean fallback) {
        String raw = query.get(name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes" -> true;
            case "false", "0", "no" -> false;
            default -> throw new ApiBadRequestException(
                    "Query parameter \"" + name + "\" must be true or false");
        };
    }

    // ==================== static helpers ====================

    /** Parses a UUID; throws {@link ApiBadRequestException} on a malformed one. */
    public static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new ApiBadRequestException("\"" + raw + "\" is not a valid UUID");
        }
    }

    /** Splits {@code a=1&b=2} into a map; an empty or null input gives an empty map. */
    public static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> result = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return result;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            result.put(decode(key), decode(value));
        }
        return result;
    }

    private static String decode(String raw) {
        return URLDecoder.decode(raw, StandardCharsets.UTF_8);
    }

    // ==================== internal ====================

    private JsonElement require(String field) {
        if (!has(field)) {
            throw new ApiBadRequestException("Field \"" + field + "\" is missing from the JSON body");
        }
        return body.get(field);
    }

    private static int asInt(String field, JsonElement element) {
        try {
            return Integer.parseInt(element.getAsString().trim());
        } catch (RuntimeException e) {
            throw new ApiBadRequestException("Field \"" + field + "\" is not a whole number");
        }
    }

    private static double asDouble(String field, JsonElement element) {
        try {
            return Double.parseDouble(element.getAsString().trim());
        } catch (RuntimeException e) {
            throw new ApiBadRequestException("Field \"" + field + "\" is not a number");
        }
    }

    private static <E extends Enum<E>> E parseEnum(String field, String raw, Class<E> type) {
        try {
            return Enum.valueOf(type, raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            StringBuilder allowed = new StringBuilder();
            for (E constant : type.getEnumConstants()) {
                allowed.append(allowed.isEmpty() ? "" : ", ").append(constant.name());
            }
            throw new ApiBadRequestException("Field \"" + field + "\": \"" + raw
                    + "\" is not recognised (allowed: " + allowed + ")");
        }
    }
}
