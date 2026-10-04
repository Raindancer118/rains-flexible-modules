package de.raindancer.e2e;

import de.raindancer.e2e.probe.E2eProbe;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A real, unmodified Paper server in a folder of its own: staged with the plugins under test, Core,
 * and the harness's probe; started, restarted and stopped; asked over RCON; its log read.
 *
 * <pre>{@code
 * try (PaperServer server = PaperServer.builder("26.2", 111)
 *         .plugin(Path.of("speedrun-standalone/target/RainsSpeedrun-1.28.1.jar"))
 *         .seed(1).build()) {
 *     server.start();
 *     Bot alex = server.bot("Alex").join();
 *     ...
 * }
 * }</pre>
 *
 * <h2>Deterministic</h2>
 * A fixed seed, a fixed Paper build, offline mode, no connection throttle — so a bot that reconnects
 * at once is let in — and every wait has a timeout that fails the scenario rather than hanging it.
 */
public final class PaperServer implements AutoCloseable {

    private static final Pattern DONE = Pattern.compile("\\]: Done \\(");
    private static final Duration START_TIMEOUT = Duration.ofMinutes(4);

    private final Path folder;
    private final Path paperJar;
    private final List<Path> plugins;
    private final long seed;
    private final int port;
    private final int rconPort;
    private final String rconPassword = "rains-e2e";
    private final List<String> jvmFlags;
    private final java.util.Map<String, String> files;
    private Process process;
    private Rcon rcon;
    private int boots;
    private final List<Bot> bots = new ArrayList<>();

    private PaperServer(Builder builder) {
        this.folder = builder.folder;
        this.paperJar = builder.paperJar;
        this.plugins = List.copyOf(builder.plugins);
        this.seed = builder.seed;
        this.port = freePort();
        this.rconPort = freePort();
        this.jvmFlags = List.copyOf(builder.jvmFlags);
        this.files = java.util.Map.copyOf(builder.files);
    }

    public static Builder builder(String version, int build) {
        return new Builder(Downloads.paper(version, build));
    }

    /** Builds a server folder. */
    public static final class Builder {
        private final Path paperJar;
        private final List<Path> plugins = new ArrayList<>();
        private final List<String> jvmFlags = new ArrayList<>(List.of("-Xmx2G"));
        private Path folder;
        private long seed = 1;
        private final java.util.Map<String, String> files = new java.util.LinkedHashMap<>();

        private Builder(Path paperJar) {
            this.paperJar = paperJar;
        }

        /** A plugin jar to install; RainsCore from the local repository is one. */
        public Builder plugin(Path jar) {
            plugins.add(Objects.requireNonNull(jar, "jar"));
            return this;
        }

        public Builder seed(long fixed) {
            this.seed = fixed;
            return this;
        }

        /** Where the server lives — kept after the run for its logs. A fresh temporary folder by default. */
        public Builder in(Path where) {
            this.folder = where;
            return this;
        }

        /** A file written into the server folder before the first start — a plugin's settings, old data to migrate. */
        public Builder file(String relative, String content) {
            files.put(relative, content);
            return this;
        }

        public Builder jvm(String flag) {
            jvmFlags.add(flag);
            return this;
        }

        public PaperServer build() {
            if (folder == null) {
                try {
                    folder = Files.createTempDirectory("rains-e2e-server");
                } catch (IOException cannot) {
                    throw new UncheckedIOException(cannot);
                }
            }
            return new PaperServer(this);
        }
    }

    // ---------------------------------------------------------------------------- folder

    /** Writes the folder, once, before the first start: eula, properties, plugins, probe. */
    private void stage() throws IOException {
        Files.createDirectories(folder.resolve("plugins"));
        Files.copy(paperJar, folder.resolve("paper.jar"), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(folder.resolve("eula.txt"), "eula=true\n");
        Files.writeString(folder.resolve("server.properties"), String.join("\n",
                "online-mode=false",
                "enforce-secure-profile=false",
                "enable-rcon=true",
                "rcon.port=" + rconPort,
                "rcon.password=" + rconPassword,
                "server-port=" + port,
                "server-ip=127.0.0.1",
                "level-seed=" + seed,
                "spawn-protection=0",
                "view-distance=4",
                "simulation-distance=4",
                "allow-flight=true",
                "max-players=20",
                "difficulty=normal",
                "sync-chunk-writes=false",
                "") + "\n");
        // A bot that disconnects and reconnects at once is a normal thing in a scenario, not an attack.
        Files.writeString(folder.resolve("bukkit.yml"), "settings:\n  connection-throttle: -1\n");
        for (Path plugin : plugins) {
            Files.copy(plugin, folder.resolve("plugins").resolve(plugin.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
        writeProbe(folder.resolve("plugins").resolve("E2eProbe.jar"));
        for (var file : files.entrySet()) {
            Path target = folder.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue());
        }
    }

    /** The probe plugin, packed from this harness's own classes — nothing to build or ship separately. */
    private static void writeProbe(Path jar) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            out.putNextEntry(new JarEntry("plugin.yml"));
            out.write(String.join("\n",
                    "name: E2eProbe",
                    "version: '1'",
                    "main: " + E2eProbe.class.getName(),
                    "api-version: '1.21'",
                    "folia-supported: true",
                    "commands:",
                    "  e2e:",
                    "    description: Drives the end-to-end harness",
                    "") .getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            String path = E2eProbe.class.getName().replace('.', '/') + ".class";
            try (InputStream bytes = E2eProbe.class.getClassLoader().getResourceAsStream(path)) {
                out.putNextEntry(new JarEntry(path));
                Objects.requireNonNull(bytes, path).transferTo(out);
                out.closeEntry();
            }
        }
    }

    // ---------------------------------------------------------------------------- lifecycle

    /** Starts it and waits until it is done loading and RCON answers. */
    public PaperServer start() {
        try {
            if (boots == 0) {
                stage();
            }
            boots++;
            Files.deleteIfExists(folder.resolve("logs").resolve("latest.log"));
            List<String> command = new ArrayList<>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.addAll(jvmFlags);
            command.addAll(List.of("-Dcom.mojang.eula.agree=true", "-jar", "paper.jar", "--nogui"));
            process = new ProcessBuilder(command).directory(folder.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(folder.resolve("stdout-" + boots + ".log").toFile()))
                    .start();
            Await.until("the server is done loading (boot " + boots + ")", START_TIMEOUT,
                    () -> {
                        if (!process.isAlive()) {
                            throw new IllegalStateException("the server died while starting — see "
                                    + folder.resolve("stdout-" + boots + ".log"));
                        }
                        return log().lines().anyMatch(line -> DONE.matcher(line).find());
                    });
            rcon = Await.value("RCON answers", Duration.ofSeconds(30), () -> {
                try {
                    return Rcon.connect("127.0.0.1", rconPort, rconPassword);
                } catch (IOException notYet) {
                    return null;
                }
            });
            return this;
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    /** Stops it cleanly: every bot leaves first, then {@code stop}, and the process is waited for. */
    public void stop() {
        for (Bot bot : List.copyOf(bots)) {
            bot.leave();
        }
        bots.clear();
        if (process == null || !process.isAlive()) {
            return;
        }
        try {
            console("stop");
        } catch (RuntimeException alreadyGoing) {
            // the process may be on its way down already
        }
        if (rcon != null) {
            rcon.close();
            rcon = null;
        }
        try {
            if (!process.waitFor(90, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor(20, java.util.concurrent.TimeUnit.SECONDS);
                throw new IllegalStateException("the server did not stop within 90 s");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Stops it and starts it again in the same folder — what a real restart does to a plugin. */
    public PaperServer restart() {
        stop();
        return start();
    }

    @Override
    public void close() {
        try {
            stop();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    // ---------------------------------------------------------------------------- asking

    /** A console command; what it answered, colour codes taken out. */
    public String console(String command) {
        try {
            return rcon.run(command);
        } catch (IOException failed) {
            throw new UncheckedIOException("RCON: " + command, failed);
        }
    }

    /** Runs {@code command} as {@code player} — for a client that cannot type, through the probe. */
    public void runAs(String player, String command) {
        console("e2e as " + player + " " + (command.startsWith("/") ? command.substring(1) : command));
    }

    /** A new bot for this server, not yet connected. */
    public Bot bot(String name) {
        Bot bot = new Bot(this, name);
        bots.add(bot);
        return bot;
    }

    void forget(Bot bot) {
        bots.remove(bot);
    }

    public int port() {
        return port;
    }

    public Path folder() {
        return folder;
    }

    /** This boot's log so far. */
    public String log() {
        Path latest = folder.resolve("logs").resolve("latest.log");
        try {
            return Files.exists(latest) ? Files.readString(latest).replaceAll("\u001B\\[[0-9;]*m", "") : "";
        } catch (IOException unreadable) {
            return "";
        }
    }

    /** Waits until the log has a line matching {@code pattern}. */
    public void awaitLog(String pattern, Duration within) {
        Pattern wanted = Pattern.compile(pattern);
        Await.until("the log says /" + pattern + "/", within, () -> log().lines().anyMatch(line -> wanted.matcher(line).find()));
    }

    /** The lines of this boot's log matching {@code which}. */
    public List<String> logLines(Predicate<String> which) {
        return log().lines().filter(which).toList();
    }

    /** Every ERROR line of this boot that names one of {@code plugins}. */
    public List<String> errorsFrom(String... plugins) {
        return log().lines().filter(line -> line.contains("ERROR]"))
                .filter(line -> Stream.of(plugins).anyMatch(line::contains)).toList();
    }

    /** What the probe saw: every menu, click and command of every boot, one JSON line each. */
    public Path events() {
        return E2eProbe.eventsIn(folder);
    }

    /** What the probe recorded so far, as text — empty before its first event. */
    public String eventsText() {
        try {
            return Files.exists(events()) ? Files.readString(events()) : "";
        } catch (IOException unreadable) {
            return "";
        }
    }

    /** A file in the server folder. */
    public Path file(String relative) {
        return folder.resolve(relative);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException cannot) {
            throw new UncheckedIOException(cannot);
        }
    }

    /** Copies the server's logs and the probe's events to {@code target} — what CI uploads. */
    public void keepLogsIn(Path target) {
        try {
            Files.createDirectories(target);
            try (Stream<Path> files = Files.list(folder)) {
                for (Path file : files.filter(path -> path.getFileName().toString().startsWith("stdout-")).toList()) {
                    Files.copy(file, target.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Path logs = folder.resolve("logs");
            if (Files.isDirectory(logs)) {
                try (Stream<Path> files = Files.list(logs)) {
                    for (Path file : files.toList()) {
                        Files.copy(file, target.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            if (Files.exists(events())) {
                Files.copy(events(), target.resolve("events.log"), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }
}
