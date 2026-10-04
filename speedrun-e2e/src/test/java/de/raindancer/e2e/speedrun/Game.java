package de.raindancer.e2e.speedrun;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import de.raindancer.e2e.Coverage;
import de.raindancer.e2e.E2e;
import de.raindancer.e2e.PaperServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * RainsSpeedrun on a real server for one scenario: RainsCore and the plugin this build made, a fixed
 * seed for the lobby's world, and helpers for what every scenario does — admins and players, settings,
 * waiting for the lobby.
 */
final class Game implements AutoCloseable {

    /** The plugin's data folder in the server — where its settings and its old data live. */
    static final String DATA = "plugins/RainsSpeedrun/";

    final PaperServer server;
    private final String scenario;

    private Game(String scenario, PaperServer server) {
        this.scenario = scenario;
        this.server = server;
    }

    /** A server for {@code scenario}, the lobby's own settings and any other files written first. */
    static Game start(String scenario, Map<String, String> settings, Map<String, String> files) {
        StringBuilder config = new StringBuilder();
        Map<String, String> all = new LinkedHashMap<>();
        // The lobby's world is made from seed 1, every run: the same map, the same spawn, the same answers.
        all.put("seed-mode", "FIXED");
        all.put("seed", "'1'");
        all.putAll(settings);
        all.forEach((key, value) -> config.append(key).append(": ").append(value).append('\n'));
        PaperServer.Builder builder = PaperServer.builder(E2e.PAPER_VERSION, E2e.PAPER_BUILD)
                .seed(1)
                .in(E2e.serverFolder(scenario))
                .plugin(Path.of(System.getProperty("e2e.rainscore")))
                .plugin(pluginJar())
                .file(DATA + "config.yml", config.toString());
        files.forEach(builder::file);
        PaperServer server = builder.build();
        Game game = new Game(scenario, server);
        try {
            server.start();
            server.awaitLog("Speedrun lobby is up", Duration.ofSeconds(60));
        } catch (RuntimeException | AssertionError failed) {
            game.close();
            throw failed;
        }
        return game;
    }

    static Game start(String scenario) {
        return start(scenario, Map.of(), Map.of());
    }

    static Game start(String scenario, Map<String, String> settings) {
        return start(scenario, settings, Map.of());
    }

    private static Path pluginJar() {
        Path folder = Path.of(System.getProperty("e2e.plugin.dir"));
        try (Stream<Path> jars = Files.list(folder)) {
            return jars.filter(path -> path.getFileName().toString().matches("RainsSpeedrun-.*\\.jar"))
                    .filter(path -> !path.getFileName().toString().startsWith("original-"))
                    .findFirst().orElseThrow(() -> new IllegalStateException("no RainsSpeedrun jar in " + folder
                            + " — build speedrun-standalone first"));
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    // ---------------------------------------------------------------------------- people

    /** A player who joins, made an operator — every node. */
    Bot admin(String name) {
        Bot bot = server.bot(name).join();
        server.console("op " + name);
        Await.ticks(5);
        return bot;
    }

    /** An ordinary player — the nodes every player has, nothing more. */
    Bot player(String name) {
        return server.bot(name).join();
    }

    // ---------------------------------------------------------------------------- settings

    /** Sets a setting from the console, as {@code /settings set} does, and waits for it to say so. */
    void set(String key, String value) {
        String answer = server.console("settings set " + key + " " + value);
        if (!answer.contains("is now")) {
            throw new AssertionError("settings set " + key + " " + value + " answered: " + answer);
        }
    }

    /** The setting's value, as {@code /settings get} reads it. */
    String get(String key) {
        return server.console("settings get " + key);
    }

    // ---------------------------------------------------------------------------- the lobby

    /** Waits until {@code bot} stands in the lobby with its items — the lobby is ready for a start. */
    static void awaitLobbyItems(Bot bot) {
        bot.expectWorld("speedrun");
        bot.expectItem("the speedrun menu compass", item -> item.tag("speedrun-lobby-item").filter("menu"::equals).isPresent());
    }

    /** Starts a run the way staff do: /speedrun start, the pre-flight page, its start button. */
    static void startRun(Bot staff) {
        startRun(staff, staff);
    }

    /**
     * The same, for staff who do not race themselves: the countdown's bar is the racers' alone, so it is
     * looked for on {@code racer}.
     */
    static void startRun(Bot staff, Bot racer) {
        staff.run("speedrun start");
        staff.awaitWindow("Before the");
        staff.forgetChat();
        staff.click("Start the countdown");
        racer.expectBossBar("");
    }

    /** Waits until {@code bot} races: its lobby items gone and the run's sidebar on screen. */
    static void awaitRacing(Bot bot) {
        Await.until(() -> bot + " races — lobby items gone (carries " + bot.items() + ", was told " + bot.chatText() + ")",
                Duration.ofSeconds(20), () -> bot.carrying(item -> item.tag("speedrun-lobby-item").isPresent()).isEmpty());
        Await.until(bot + " sees the run's sidebar", Duration.ofSeconds(20), () -> !bot.sidebar().isEmpty());
    }

    /** Waits until {@code bot} races — its lobby items gone — where no sidebar is expected. */
    static void awaitRacingWithoutSidebar(Bot bot) {
        bot.expectNoItem("lobby items", item -> item.tag("speedrun-lobby-item").isPresent());
    }

    /** Makes sure {@code bot} races the next run — /speedrun spectate toggles, and says which way. */
    static void makeRacing(Bot bot) {
        Bot.Answer answer = bot.answer(() -> bot.run("speedrun spectate"),
                said -> said.says("not racing") || said.says("racing again"));
        if (answer.says("not racing")) {
            bot.runAndExpect("speedrun spectate", "racing again");
        }
    }

    /** Records an id as shown by this scenario — after its assertion. */
    static void covered(String... ids) {
        for (String id : ids) {
            Coverage.done(id);
        }
    }

    @Override
    public void close() {
        try {
            server.keepLogsIn(E2e.logsFolder(scenario));
        } finally {
            server.close();
        }
    }
}
