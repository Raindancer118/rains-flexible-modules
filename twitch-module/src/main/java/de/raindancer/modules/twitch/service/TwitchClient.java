package de.raindancer.modules.twitch.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.raindancer.modules.twitch.model.Observation;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Asks Twitch's Helix API whether a channel is live, with an app token (client credentials: it reads public data and
 * can do nothing else). The token is kept until shortly before it runs out. Every failure is {@link Observation#UNKNOWN}.
 */
public final class TwitchClient {

    private static final URI TOKEN = URI.create("https://id.twitch.tv/oauth2/token");
    private static final String STREAMS = "https://api.twitch.tv/helix/streams?user_login=";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final String clientId;
    private final String clientSecret;
    private String token = "";
    private long tokenUntil;

    public TwitchClient(String clientId, String clientSecret) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    /** Blocking: call it off the server's threads. */
    public Observation look(String channel) {
        try {
            if (!ensureToken()) {
                return Observation.UNKNOWN;
            }
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                            URI.create(STREAMS + URLEncoder.encode(channel, StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(10)).header("Client-Id", clientId)
                    .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401) {
                tokenUntil = 0;
                return Observation.UNKNOWN;
            }
            return response.statusCode() == 200 ? StreamParser.read(response.body(), channel) : Observation.UNKNOWN;
        } catch (IOException | RuntimeException failed) {
            return Observation.UNKNOWN;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Observation.UNKNOWN;
        }
    }

    private boolean ensureToken() throws IOException, InterruptedException {
        if (!token.isEmpty() && System.currentTimeMillis() < tokenUntil) {
            return true;
        }
        String form = "client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8)
                + "&grant_type=client_credentials";
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(TOKEN).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return false;
        }
        JsonObject answer = JsonParser.parseString(response.body()).getAsJsonObject();
        token = answer.get("access_token").getAsString();
        long seconds = answer.has("expires_in") ? answer.get("expires_in").getAsLong() : 3600;
        tokenUntil = System.currentTimeMillis() + Math.max(60, seconds - 300) * 1000L;
        return true;
    }
}
