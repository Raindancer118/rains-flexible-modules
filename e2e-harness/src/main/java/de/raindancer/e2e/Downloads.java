package de.raindancer.e2e;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

/**
 * What a run needs from outside, fetched once and kept: the Paper server jar, and the vanilla
 * server and client jars of the same game version — the server one for the item registry it reports,
 * the client one for the item textures a menu picture is drawn from. Nothing of Mojang's is ever
 * written anywhere but this cache.
 *
 * <p>The cache is {@code $RAINS_E2E_CACHE}, or {@code ~/.cache/rains-e2e} — what CI caches between
 * runs. Every download is checked against the checksum its source publishes, and only moved into
 * the cache once it matches, so a broken download never poisons the next run.
 */
public final class Downloads {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();
    private static final String MOJANG_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    private Downloads() {
    }

    public static Path cache() {
        String configured = System.getenv("RAINS_E2E_CACHE");
        Path folder = configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), ".cache", "rains-e2e")
                : Path.of(configured);
        try {
            Files.createDirectories(folder);
        } catch (IOException cannot) {
            throw new IllegalStateException("cannot create the e2e cache " + folder, cannot);
        }
        return folder;
    }

    /** Paper {@code version} build {@code build}, from Paper's own download API. */
    public static Path paper(String version, int build) {
        Path jar = cache().resolve("paper-" + version + "-" + build + ".jar");
        if (Files.exists(jar)) {
            return jar;
        }
        JsonObject meta = json("https://fill.papermc.io/v3/projects/paper/versions/" + version + "/builds/" + build);
        JsonObject download = meta.getAsJsonObject("downloads").getAsJsonObject("server:default");
        String sha256 = download.getAsJsonObject("checksums").get("sha256").getAsString();
        fetch(download.get("url").getAsString(), jar, "SHA-256", sha256);
        return jar;
    }

    /**
     * A third-party plugin a scenario plays against, pinned by its SHA-1 — a jar that changed under the
     * same URL is refused rather than tested.
     */
    public static Path pinned(String fileName, String url, String sha1) {
        Path jar = cache().resolve("plugins").resolve(fileName);
        if (Files.exists(jar)) {
            return jar;
        }
        try {
            Files.createDirectories(jar.getParent());
        } catch (IOException cannot) {
            throw new IllegalStateException("cannot create " + jar.getParent(), cannot);
        }
        fetch(url, jar, "SHA-1", sha1);
        return jar;
    }

    /** The vanilla server jar of {@code version} — for its data reports. */
    public static Path vanillaServer(String version) {
        return vanilla(version, "server");
    }

    /** The vanilla client jar of {@code version} — for its textures, read at run time, never kept elsewhere. */
    public static Path vanillaClient(String version) {
        return vanilla(version, "client");
    }

    private static Path vanilla(String version, String side) {
        Path jar = cache().resolve("minecraft-" + side + "-" + version + ".jar");
        if (Files.exists(jar)) {
            return jar;
        }
        JsonObject manifest = json(MOJANG_MANIFEST);
        String versionUrl = null;
        for (var entry : manifest.getAsJsonArray("versions")) {
            JsonObject one = entry.getAsJsonObject();
            if (one.get("id").getAsString().equals(version)) {
                versionUrl = one.get("url").getAsString();
            }
        }
        if (versionUrl == null) {
            throw new IllegalStateException("Mojang does not list Minecraft " + version);
        }
        JsonObject download = json(versionUrl).getAsJsonObject("downloads").getAsJsonObject(side);
        fetch(download.get("url").getAsString(), jar, "SHA-1", download.get("sha1").getAsString());
        return jar;
    }

    private static JsonObject json(String url) {
        for (int attempt = 1; ; attempt++) {
            try {
                HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(60)).header("User-Agent", "rains-e2e").build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IOException("HTTP " + response.statusCode() + " for " + url);
                }
                return JsonParser.parseString(response.body()).getAsJsonObject();
            } catch (IOException | InterruptedException failed) {
                if (attempt >= 3) {
                    throw new IllegalStateException("could not read " + url, failed);
                }
                pause(attempt);
            }
        }
    }

    private static void fetch(String url, Path target, String algorithm, String expected) {
        for (int attempt = 1; ; attempt++) {
            Path part = target.resolveSibling(target.getFileName() + ".part");
            try {
                HttpResponse<InputStream> response = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofMinutes(5)).header("User-Agent", "rains-e2e").build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) {
                    throw new IOException("HTTP " + response.statusCode() + " for " + url);
                }
                try (InputStream body = response.body()) {
                    Files.copy(body, part, StandardCopyOption.REPLACE_EXISTING);
                }
                String actual = HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(Files.readAllBytes(part)));
                if (!actual.equalsIgnoreCase(expected)) {
                    throw new IOException(url + " has " + algorithm + " " + actual + ", expected " + expected);
                }
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (IOException | InterruptedException | java.security.NoSuchAlgorithmException failed) {
                try {
                    Files.deleteIfExists(part);
                } catch (IOException ignored) {
                    // the next attempt overwrites it anyway
                }
                if (attempt >= 3) {
                    throw new IllegalStateException("could not download " + url, failed);
                }
                pause(attempt);
            }
        }
    }

    private static void pause(int attempt) {
        try {
            Thread.sleep(2000L * attempt);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
