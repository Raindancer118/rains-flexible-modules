package de.raindancer.modules.hungergames.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.function.Consumer;

/**
 * What an API handler answers with: an HTTP status and a JSON body. A handler builds and returns one of
 * these rather than writing to the {@code HttpExchange} itself — that is what makes every handler in
 * this package testable with a plain method call, with the transport (headers, status codes going out on
 * the wire, the response stream) confined to {@link HttpApiService}.
 *
 * <p>A record rather than a service: it is a value, built fresh for every answer, with nothing in it a
 * settings reload could touch.
 */
public record ApiResponse(int status, JsonObject body) {

    /** {@code 200 {"ok": true}} */
    public static ApiResponse ok() {
        JsonObject json = new JsonObject();
        json.addProperty("ok", true);
        return new ApiResponse(200, json);
    }

    /** {@code 200 {"ok": true, ...}}, with extra fields added by the caller. */
    public static ApiResponse ok(Consumer<JsonObject> filler) {
        ApiResponse response = ok();
        filler.accept(response.body());
        return response;
    }

    /** {@code 200} with an arbitrary body — the shape most read endpoints return. */
    public static ApiResponse json(JsonObject body) {
        return new ApiResponse(200, body);
    }

    /** {@code 200 {"<field>": [...]}} */
    public static ApiResponse json(String field, JsonArray array) {
        JsonObject json = new JsonObject();
        json.add(field, array);
        return new ApiResponse(200, json);
    }

    public static ApiResponse error(int status, String message) {
        JsonObject json = new JsonObject();
        json.addProperty("error", message);
        return new ApiResponse(status, json);
    }

    /** {@code 400} — the request itself was wrong. */
    public static ApiResponse badRequest(String message) {
        return error(400, message);
    }

    /** {@code 404} — nothing by that name or at that path. */
    public static ApiResponse notFound(String message) {
        return error(404, message);
    }

    /**
     * {@code 409} — the action cannot happen in the game's current state (phase locked, team full, the
     * named player offline, …).
     */
    public static ApiResponse conflict(String message) {
        return error(409, message);
    }

    /** {@link #ok()} for {@code null}, otherwise {@link #conflict(String)} — for a service that answers
     * with an error message or nothing at all. */
    public static ApiResponse okOrConflict(String errorOrNull) {
        return errorOrNull == null ? ok() : conflict(errorOrNull);
    }

    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }
}
