package de.raindancer.e2e;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * The real Minecraft client — Mojang's own, unmodified, in offline mode — on a virtual display with
 * software rendering, joined to the test server: what a screenshot of a menu is taken with, so the
 * picture is the game's own and not an imitation of it.
 *
 * <p>Everything it needs is fetched once into the e2e cache: the client jar, its libraries, and the
 * game's assets except sounds. None of it is ever copied anywhere else. The display is an {@code Xvfb}
 * of its own; OpenGL is Mesa's software renderer, which is what a CI runner without a GPU has too.
 *
 * <p>It cannot be scripted from outside, so it is driven from the server: commands through
 * {@link PaperServer#runAs}, clicks and item uses through the probe.
 */
public final class RealClient implements AutoCloseable {

    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20)).build();
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;

    private final String name;
    private final String display;
    private final Process xvfb;
    private final Process game;
    private final Path folder;

    private RealClient(String name, String display, Process xvfb, Process game, Path folder) {
        this.name = name;
        this.display = display;
        this.xvfb = xvfb;
        this.game = game;
        this.folder = folder;
    }

    /** Starts a display and the client, joins {@code server} as {@code name}, and waits until it is in. */
    public static RealClient join(PaperServer server, String name) {
        String version = E2e.MINECRAFT_VERSION;
        try {
            Path cache = Downloads.cache().resolve("client-" + version);
            Files.createDirectories(cache);
            JsonObject meta = versionJson(version, cache);
            List<Path> classpath = libraries(meta, cache);
            classpath.add(Downloads.vanillaClient(version));
            String assetIndex = assets(meta, cache);

            Path folder = E2e.out().resolve("client-" + name);
            Files.createDirectories(folder);
            // No first-start screens, no sound, the nearest chunks only — nothing a screenshot waits on.
            Files.writeString(folder.resolve("options.txt"), String.join("\n",
                    "onboardAccessibility:false", "skipMultiplayerWarning:true", "joinedFirstServer:true",
                    "tutorialStep:none", "renderDistance:2", "simulationDistance:5", "soundCategory_master:0.0",
                    "narrator:0", "guiScale:2", "pauseOnLostFocus:false", "hideServerAddress:true", ""));

            String display = ":" + (90 + (int) (ProcessHandle.current().pid() % 9));
            Process xvfb = new ProcessBuilder("Xvfb", display, "-screen", "0", WIDTH + "x" + HEIGHT + "x24", "-nolisten", "tcp")
                    .redirectErrorStream(true).redirectOutput(folder.resolve("xvfb.log").toFile()).start();
            Await.ticks(30);
            if (!xvfb.isAlive()) {
                throw new IllegalStateException("Xvfb did not start — see " + folder.resolve("xvfb.log"));
            }

            List<String> command = new ArrayList<>(List.of(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx2G",
                    "-cp", String.join(java.io.File.pathSeparator, classpath.stream().map(Path::toString).toList()),
                    meta.get("mainClass").getAsString(),
                    "--username", name, "--version", version, "--gameDir", folder.toString(),
                    "--assetsDir", cache.resolve("assets").toString(), "--assetIndex", assetIndex,
                    "--uuid", UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes()).toString().replace("-", ""),
                    "--accessToken", "0", "--userType", "legacy", "--versionType", "release",
                    "--width", String.valueOf(WIDTH), "--height", String.valueOf(HEIGHT),
                    "--quickPlayMultiplayer", "127.0.0.1:" + server.port()));
            ProcessBuilder builder = new ProcessBuilder(command).directory(folder.toFile()).redirectErrorStream(true)
                    .redirectOutput(folder.resolve("client.log").toFile());
            Map<String, String> env = builder.environment();
            env.put("DISPLAY", display);
            env.put("LIBGL_ALWAYS_SOFTWARE", "1");
            env.put("__GLX_VENDOR_LIBRARY_NAME", "mesa");
            Process game = builder.start();
            RealClient client = new RealClient(name, display, xvfb, game, folder);
            try {
                server.awaitLog(name + " joined the game", Duration.ofMinutes(3));
                // Terrain around the spawn, and the client done fading in.
                Await.ticks(200);
            } catch (RuntimeException | AssertionError failed) {
                client.close();
                throw failed;
            }
            return client;
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    /**
     * The pointer to the screen's corner. A menu opens with it in the middle, over a slot, and the
     * picture would carry that item's tooltip over half the page. Moved by a throwaway JVM with AWT's
     * Robot on this display — no xdotool needed, the JDK is already here.
     */
    private void pointerAway() throws IOException, InterruptedException {
        Path source = folder.resolve("PointerAway.java");
        if (!Files.exists(source)) {
            Files.writeString(source, """
                    public class PointerAway {
                        public static void main(String[] args) throws Exception {
                            java.awt.Robot robot = new java.awt.Robot();
                            robot.mouseMove(2, 2);
                            robot.delay(100);
                        }
                    }
                    """);
        }
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.awt.headless=false", source.toString())
                .redirectErrorStream(true).redirectOutput(folder.resolve("pointer.log").toFile());
        builder.environment().put("DISPLAY", display);
        Process move = builder.start();
        if (!move.waitFor(60, TimeUnit.SECONDS) || move.exitValue() != 0) {
            throw new IllegalStateException("the pointer could not be moved — see " + folder.resolve("pointer.log"));
        }
    }

    /** What the screen shows now, as a PNG at {@code target}. */
    public Path screenshot(Path target) {
        try {
            pointerAway();
            Await.ticks(10);
            Files.createDirectories(target.getParent());
            Process capture = new ProcessBuilder("import", "-display", display, "-window", "root", target.toString())
                    .redirectErrorStream(true).redirectOutput(folder.resolve("capture.log").toFile()).start();
            if (!capture.waitFor(30, TimeUnit.SECONDS) || capture.exitValue() != 0 || !Files.exists(target)) {
                throw new IllegalStateException("the screen could not be captured — see " + folder.resolve("capture.log"));
            }
            return target;
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    public String name() {
        return name;
    }

    public boolean isRunning() {
        return game.isAlive();
    }

    @Override
    public void close() {
        game.destroy();
        try {
            if (!game.waitFor(20, TimeUnit.SECONDS)) {
                game.destroyForcibly();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        xvfb.destroy();
    }

    // ---------------------------------------------------------------------------- what it needs

    private static JsonObject versionJson(String version, Path cache) throws IOException {
        Path file = cache.resolve(version + ".json");
        if (!Files.exists(file)) {
            JsonObject manifest = json("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
            for (JsonElement entry : manifest.getAsJsonArray("versions")) {
                if (entry.getAsJsonObject().get("id").getAsString().equals(version)) {
                    download(entry.getAsJsonObject().get("url").getAsString(), file);
                }
            }
        }
        return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    }

    /** Every library the client needs on Linux, natives included — LWJGL loads them from the classpath. */
    private static List<Path> libraries(JsonObject meta, Path cache) throws IOException {
        List<Path> paths = new ArrayList<>();
        List<String[]> wanted = new ArrayList<>();
        for (JsonElement element : meta.getAsJsonArray("libraries")) {
            JsonObject library = element.getAsJsonObject();
            if (!allowedOnLinux(library.getAsJsonArray("rules"))) {
                continue;
            }
            JsonObject downloads = library.getAsJsonObject("downloads");
            if (downloads == null || !downloads.has("artifact")) {
                continue;
            }
            JsonObject artifact = downloads.getAsJsonObject("artifact");
            Path target = cache.resolve("libraries").resolve(artifact.get("path").getAsString());
            paths.add(target);
            if (!Files.exists(target)) {
                wanted.add(new String[]{artifact.get("url").getAsString(), target.toString()});
            }
        }
        fetchAll(wanted);
        return paths;
    }

    private static boolean allowedOnLinux(JsonArray rules) {
        if (rules == null) {
            return true;
        }
        boolean allowed = false;
        for (JsonElement element : rules) {
            JsonObject rule = element.getAsJsonObject();
            JsonObject os = rule.getAsJsonObject("os");
            if (os == null || "linux".equals(os.get("name").getAsString())) {
                allowed = rule.get("action").getAsString().equals("allow");
            }
        }
        return allowed;
    }

    /** The asset index and every asset but sounds — a screenshot hears nothing. @return the index's id */
    private static String assets(JsonObject meta, Path cache) throws IOException {
        JsonObject index = meta.getAsJsonObject("assetIndex");
        String id = index.get("id").getAsString();
        Path indexFile = cache.resolve("assets").resolve("indexes").resolve(id + ".json");
        if (!Files.exists(indexFile)) {
            download(index.get("url").getAsString(), indexFile);
        }
        JsonObject objects = JsonParser.parseString(Files.readString(indexFile)).getAsJsonObject().getAsJsonObject("objects");
        List<String[]> wanted = new ArrayList<>();
        for (String key : objects.keySet()) {
            if (key.startsWith("minecraft/sounds/") || key.endsWith(".ogg")) {
                continue;
            }
            String hash = objects.getAsJsonObject(key).get("hash").getAsString();
            Path target = cache.resolve("assets").resolve("objects").resolve(hash.substring(0, 2)).resolve(hash);
            if (!Files.exists(target)) {
                wanted.add(new String[]{"https://resources.download.minecraft.net/" + hash.substring(0, 2) + "/" + hash,
                        target.toString()});
            }
        }
        fetchAll(wanted);
        return id;
    }

    private static void fetchAll(List<String[]> wanted) {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> running = new ArrayList<>();
            for (String[] one : wanted) {
                running.add(pool.submit(() -> {
                    download(one[0], Path.of(one[1]));
                    return null;
                }));
            }
            for (Future<?> each : running) {
                each.get(10, TimeUnit.MINUTES);
            }
        } catch (Exception failed) {
            throw new IllegalStateException("could not fetch what the client needs", failed);
        } finally {
            pool.shutdownNow();
        }
    }

    private static JsonObject json(String url) throws IOException {
        try {
            return JsonParser.parseString(HTTP.send(HttpRequest.newBuilder(URI.create(url)).build(),
                    HttpResponse.BodyHandlers.ofString()).body()).getAsJsonObject();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(interrupted);
        }
    }

    private static void download(String url, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path part = target.resolveSibling(target.getFileName() + ".part");
        for (int attempt = 1; ; attempt++) {
            try {
                HttpResponse<InputStream> response = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofMinutes(3)).build(), HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) {
                    throw new IOException("HTTP " + response.statusCode() + " for " + url);
                }
                try (InputStream body = response.body()) {
                    Files.copy(body, part, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (IOException | InterruptedException failed) {
                if (failed instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                if (attempt >= 3) {
                    throw new IOException("could not download " + url, failed);
                }
            }
        }
    }
}
