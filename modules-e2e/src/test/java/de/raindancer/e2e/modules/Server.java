package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import de.raindancer.e2e.E2e;
import de.raindancer.e2e.PaperServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

/** A real Paper server with RainsCore and the named standalone module plugins, for one scenario. */
final class Server implements AutoCloseable {

    final PaperServer paper;
    private final String scenario;

    private Server(String scenario, PaperServer paper) {
        this.scenario = scenario;
        this.paper = paper;
    }

    /**
     * @param plugins {@code <standalone-dir>:<jar-name-regex>}, e.g. {@code cosmetics-standalone:RainsCosmetics-.*}
     * @param upLines a log line per module that says it enabled
     */
    static Server start(String scenario, List<String> plugins, List<String> upLines) {
        PaperServer.Builder builder = PaperServer.builder(E2e.PAPER_VERSION, E2e.PAPER_BUILD)
                .seed(1)
                .in(E2e.serverFolder(scenario))
                .plugin(Path.of(System.getProperty("e2e.rainscore")));
        for (String plugin : plugins) {
            String[] parts = plugin.split(":", 2);
            builder.plugin(jar(parts[0], parts[1]));
        }
        Server server = new Server(scenario, builder.build());
        try {
            server.paper.start();
            for (String line : upLines) {
                server.paper.awaitLog(line, Duration.ofSeconds(60));
            }
        } catch (RuntimeException | AssertionError failed) {
            server.close();
            throw failed;
        }
        return server;
    }

    private static Path jar(String module, String pattern) {
        Path folder = Path.of(System.getProperty("e2e.reactor")).resolve(module).resolve("target");
        try (Stream<Path> jars = Files.list(folder)) {
            return jars.filter(path -> path.getFileName().toString().matches(pattern + "\\.jar"))
                    .filter(path -> !path.getFileName().toString().startsWith("original-"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no " + pattern + " in " + folder + " — build it first"));
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    /** A player who joins and is made an operator. */
    Bot admin(String name) {
        Bot bot = paper.bot(name).join();
        paper.console("op " + name);
        Await.ticks(5);
        return bot;
    }

    /** An ordinary player. */
    Bot player(String name) {
        return paper.bot(name).join();
    }

    String console(String command) {
        return paper.console(command);
    }

    @Override
    public void close() {
        try {
            paper.keepLogsIn(E2e.logsFolder(scenario));
        } finally {
            paper.close();
        }
    }
}
