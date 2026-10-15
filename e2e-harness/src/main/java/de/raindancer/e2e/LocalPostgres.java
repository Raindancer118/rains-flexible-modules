package de.raindancer.e2e;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A throwaway PostgreSQL for a scenario that runs servers as a network — the way Lilly runs: every server's
 * {@code network.yml} points at the one database. A cluster of its own under the scenario's folder, on a free port,
 * trusting local connections, stopped and deleted with {@link #close()}. Needs {@code initdb} and {@code postgres} on
 * the PATH.
 */
public final class LocalPostgres implements AutoCloseable {

    public static final String USER = "rains";
    public static final String DATABASE = "rains";

    private final Path folder;
    private final int port;
    private final Process server;

    private LocalPostgres(Path folder, int port, Process server) {
        this.folder = folder;
        this.port = port;
        this.server = server;
    }

    public static LocalPostgres start(Path folder) {
        try {
            if (Files.exists(folder)) {
                E2e.deleteTree(folder);
            }
            Path data = Files.createDirectories(folder).resolve("data");
            run(List.of("initdb", "-D", data.toString(), "-A", "trust", "-U", USER, "--no-sync"), folder);
            int port = freePort();
            Process server = new ProcessBuilder("postgres", "-D", data.toString(), "-p", String.valueOf(port),
                    // TCP only: a socket path under a deep build folder is longer than the 107 bytes Unix allows.
                    "-c", "unix_socket_directories=", "-c", "listen_addresses=127.0.0.1", "-c", "max_connections=100",
                    "-c", "fsync=off")
                    .redirectErrorStream(true)
                    .redirectOutput(folder.resolve("postgres.log").toFile())
                    .start();
            LocalPostgres postgres = new LocalPostgres(folder, port, server);
            Await.until("PostgreSQL takes connections", Duration.ofSeconds(30), () -> {
                if (!server.isAlive()) {
                    throw new IllegalStateException("PostgreSQL died — see " + folder.resolve("postgres.log"));
                }
                return runQuietly(List.of("pg_isready", "-h", "127.0.0.1", "-p", String.valueOf(port)), folder);
            });
            run(List.of("createdb", "-h", "127.0.0.1", "-p", String.valueOf(port), "-U", USER, DATABASE), folder);
            return postgres;
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    public int port() {
        return port;
    }

    /** A server's {@code plugins/RainsCore/network.yml} on this database. */
    public String networkYml(String serverId, String group) {
        return "server:\n  id: " + serverId + "\n  group: " + group + "\n"
                + "database:\n  type: postgresql\n  postgresql:\n    host: 127.0.0.1\n    port: " + port
                + "\n    database: " + DATABASE + "\n    user: " + USER + "\n    password: \"\"\n    pool-size: 5\n"
                + "    ssl: disable\n";
    }

    @Override
    public void close() {
        server.destroy();
        try {
            if (!server.waitFor(20, TimeUnit.SECONDS)) {
                server.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void run(List<String> command, Path in) throws IOException {
        if (!runQuietly(command, in)) {
            throw new IOException(String.join(" ", command) + " failed — see " + in.resolve("tools.log"));
        }
    }

    private static boolean runQuietly(List<String> command, Path in) {
        try {
            Process process = new ProcessBuilder(command).directory(in.toFile()).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(in.resolve("tools.log").toFile())).start();
            return process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException failed) {
            if (failed instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
