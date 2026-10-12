package de.raindancer.e2e;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A Velocity proxy in front of several {@link PaperServer}s, for a scenario about a network — players going from one
 * server to another. Modern forwarding with a secret, offline mode like the servers behind it, and the BungeeCord
 * channel on, as a real network has it for plugins that send players around.
 *
 * <p>Each server behind it needs {@link #paperGlobal()} as its {@code config/paper-global.yml}.
 */
public final class VelocityProxy implements AutoCloseable {

    private static final Duration START_TIMEOUT = Duration.ofSeconds(60);

    private final Path folder;
    private final int port;
    private final String secret;
    private final Map<String, Integer> servers;
    private Process process;

    private VelocityProxy(Path folder, Map<String, Integer> servers, String secret) {
        this.folder = folder;
        this.servers = new LinkedHashMap<>(servers);
        this.secret = secret;
        this.port = freePort();
    }

    /**
     * @param servers name to port, in order — the first is where a player lands
     * @param secret  the forwarding secret every server behind it is given
     */
    public static VelocityProxy in(Path folder, Map<String, Integer> servers, String secret) {
        return new VelocityProxy(folder, servers, secret);
    }

    /** What a Paper server behind this proxy needs in {@code config/paper-global.yml}. */
    public static String paperGlobal(String secret) {
        return "proxies:\n  velocity:\n    enabled: true\n    online-mode: false\n    secret: '" + secret + "'\n";
    }

    public String paperGlobal() {
        return paperGlobal(secret);
    }

    public int port() {
        return port;
    }

    public VelocityProxy start() {
        try {
            Files.createDirectories(folder);
            Files.copy(Downloads.velocity(E2e.VELOCITY_VERSION, E2e.VELOCITY_BUILD), folder.resolve("velocity.jar"),
                    StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(folder.resolve("forwarding.secret"), secret);
            Files.writeString(folder.resolve("velocity.toml"), toml());
            Files.deleteIfExists(folder.resolve("logs").resolve("latest.log"));
            List<String> command = new ArrayList<>(List.of(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx256m",
                    "-jar", "velocity.jar"));
            process = new ProcessBuilder(command).directory(folder.toFile()).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(folder.resolve("stdout.log").toFile()))
                    .start();
            Await.until("the proxy is up", START_TIMEOUT, () -> {
                if (!process.isAlive()) {
                    throw new IllegalStateException("the proxy died while starting — see " + folder.resolve("stdout.log"));
                }
                return log().contains("Done (");
            });
            return this;
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    public String log() {
        try {
            Path stdout = folder.resolve("stdout.log");
            return Files.exists(stdout) ? Files.readString(stdout) : "";
        } catch (IOException unreadable) {
            return "";
        }
    }

    private String toml() {
        StringBuilder entries = new StringBuilder();
        servers.forEach((name, serverPort) -> entries.append(name).append(" = \"127.0.0.1:").append(serverPort)
                .append("\"\n"));
        String first = servers.keySet().iterator().next();
        return """
                config-version = "2.9"
                bind = "127.0.0.1:%d"
                motd = "e2e"
                show-max-players = 20
                online-mode = false
                force-key-authentication = false
                player-info-forwarding-mode = "MODERN"
                forwarding-secret-file = "forwarding.secret"
                announce-forge = false
                kick-existing-players = false

                [servers]
                %stry = ["%s"]

                [forced-hosts]

                [advanced]
                compression-threshold = 256
                login-ratelimit = 0
                connection-timeout = 5000
                read-timeout = 30000
                bungee-plugin-message-channel = true
                failover-on-unexpected-server-disconnect = true
                log-player-connections = true

                [query]
                enabled = false
                """.formatted(port, entries, first);
    }

    @Override
    public void close() {
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }
}
