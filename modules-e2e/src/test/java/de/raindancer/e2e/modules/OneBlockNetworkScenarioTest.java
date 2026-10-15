package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import de.raindancer.e2e.E2e;
import de.raindancer.e2e.LocalPostgres;
import de.raindancer.e2e.PaperServer;
import de.raindancer.e2e.VelocityProxy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OneBlock as Lilly's network runs it: a proxy, a survival server and the island server, both with RainsOneBlock, on
 * one PostgreSQL as a RainsCore network — the island server is the one whose id is {@code oneblock}.
 *
 * <p>What is proved here and nowhere else: {@code /island} on the survival server lands a player on the island server,
 * where they choose the money once and get an island of their own world; breaking the block brings it back and pays
 * shards; shards unlock the nether, reached by {@code /island nether}; the money gate holds; a team invite from the
 * island server reaches a player on the survival server and joining moves them over; an empty island is unloaded, an
 * admin keeps one up or down, auto-unload switched off keeps it up through a restart; and none of it logs an error.
 */
@Tag("e2e")
class OneBlockNetworkScenarioTest {

    private static final Duration WAIT = Duration.ofSeconds(90);
    private static final String FORWARDING_SECRET = "e2e-forwarding-secret";

    @Test
    @DisplayName("an island from the survival server, the block, shards, the nether, a team, unloading and holds")
    void network() throws Exception {
        String paperGlobal = VelocityProxy.paperGlobal(FORWARDING_SECRET);
        LocalPostgres postgres = LocalPostgres.start(E2e.serverFolder("oneblock-net-postgres"));

        PaperServer smp = PaperServer.forRun().seed(1).in(E2e.serverFolder("oneblock-net-smp"))
                .plugin(Path.of(System.getProperty("e2e.rainscore")))
                .plugin(jar("oneblock-standalone", "RainsOneBlock-.*"))
                .file("config/paper-global.yml", paperGlobal)
                .file("plugins/RainsCore/network.yml", postgres.networkYml("smp", "survival"))
                .build();
        PaperServer island = PaperServer.forRun().seed(1).in(E2e.serverFolder("oneblock-net-island"))
                .plugin(Path.of(System.getProperty("e2e.rainscore")))
                .plugin(jar("oneblock-standalone", "RainsOneBlock-.*"))
                .file("config/paper-global.yml", paperGlobal)
                .file("plugins/RainsCore/network.yml", postgres.networkYml("oneblock", "minigames"))
                .file("plugins/RainsOneBlock/config.yml", "unload-seconds: 10\n")
                .build();

        Map<String, Integer> servers = new LinkedHashMap<>();
        servers.put("smp", smp.port());
        servers.put("oneblock", island.port());
        try (VelocityProxy proxy = VelocityProxy.in(E2e.serverFolder("oneblock-net-proxy"), servers, FORWARDING_SECRET);
             smp; island; postgres) {
            island.start();
            island.awaitLog("This is the island server", WAIT);
            smp.start();
            proxy.start();

            // ── /island on the survival server: over to the island server, where the money is chosen once ─────────
            Bot ada = smp.bot("Ada").through(proxy.port()).join();
            ada.run("island");
            Await.until("Ada is on the island server", WAIT, () -> island.console("list").contains("Ada"));
            Await.until("she is asked how her island plays", WAIT, () -> ada.window()
                    .filter(window -> window.slotNamed("Pure OneBlock").isPresent()).isPresent());
            ada.click("Pure OneBlock");
            Await.until("and asked again, since it is for good", WAIT, () -> ada.window()
                    .filter(window -> window.slotNamed("Yes, do it").isPresent()).isPresent());
            ada.click("Yes, do it");
            ada.expectChat("Your island is ready");
            Await.until("Ada stands on her own island world", WAIT, () -> ada.world().contains("island_"));
            String world = ada.world().replace("minecraft:", "");
            assertThat(world).matches("island_[0-9a-f]{16}");

            ada.expectSidebar("Your island");
            ada.expectSidebar("Pure");
            ada.expectSidebar("Shards 0");

            // ── the block breaks, comes back, and every tenth break pays a shard ───────────────────────────────────
            island.console("gamemode creative Ada");
            ada.flying(true);
            island.console("execute in minecraft:" + world + " run tp Ada 0.5 66 2.5 180 60");
            Await.ticks(20);
            for (int i = 0; i < 10; i++) {
                ada.dig(0, 64, 0);
                Await.ticks(4);
            }
            Await.until("the block came back", WAIT, () -> !island.console("execute in minecraft:" + world
                    + " if block 0 64 0 minecraft:air").contains("passed"));
            Await.until("ten breaks paid a shard", WAIT, () -> ada.sidebar().stream().anyMatch(line -> line.contains("Shards 1")));
            ada.expectSidebar("10 blocks broken");

            // ── shards open the nether; /island nether goes there ──────────────────────────────────────────────────
            ada.run("island nether");
            ada.expectChat("still locked on your island");
            island.console("islandadmin shards Ada 500");
            ada.run("island unlock nether");
            ada.expectChat("Unlocked");
            ada.run("island nether");
            Await.until("Ada is in her island's nether", WAIT, () -> ada.world().contains(world + "_nether"));
            // The world changes on the client before the teleport is finished on the server; a trip asked for in
            // between is refused as one already under way.
            Await.ticks(40);
            ada.run("island home");
            Await.until("and home again", WAIT, () -> ada.world().equals("minecraft:" + world));

            // ── a pure island keeps SMP money out ──────────────────────────────────────────────────────────────────
            ada.run("pay Bo 5");
            ada.expectChat("plays Pure");

            // ── a team invite from the island server, read on the survival server, with the island's money ──────────
            Bot bo = smp.bot("Bo").through(proxy.port()).join();
            try {
                Await.until("the island server knows Bo is on the survival server", WAIT, () -> {
                    ada.run("island invite Bo");
                    return ada.chatText().stream().anyMatch(line -> line.contains("Invited Bo to your team"));
                });
            } catch (AssertionError none) {
                List<String> told = ada.chatText();
                throw new AssertionError(none.getMessage() + " — Ada was last told "
                        + told.subList(Math.max(0, told.size() - 5), told.size()), none);
            }
            bo.expectChat("wants you on their OneBlock team");
            assertThat(bo.chatText()).anyMatch(line -> line.contains("Pure"));
            bo.clickButtonOn("wants you on their OneBlock team", 0);
            try {
                Await.until("Bo is on the island server", WAIT, () -> island.console("list").contains("Bo"));
            } catch (AssertionError none) {
                List<String> told = bo.chatText();
                throw new AssertionError(none.getMessage() + " — Bo was last told "
                        + told.subList(Math.max(0, told.size() - 6), told.size()), none);
            }
            bo.expectChat("You are on Ada's team now");
            Await.until("Bo is on Ada's island", WAIT, () -> bo.world().equals("minecraft:" + world));
            Await.until("Bo's sidebar has him as a member", WAIT,
                    () -> bo.sidebar().stream().anyMatch(line -> line.contains("You: Member")));
            ada.run("island invite Bo");
            ada.expectChat("already on your island");

            // ── nobody on it: the island goes to sleep after the grace ─────────────────────────────────────────────
            ada.run("server smp");
            bo.run("server smp");
            Await.until("both are back on the survival server", WAIT, () -> !island.console("list").contains("Ada")
                    && !island.console("list").contains("Bo"));
            Await.until("the empty island is unloaded", WAIT, () -> info(island, "Ada").contains("loaded: false"));

            // ── an admin keeps it up, lets it go, keeps it down ────────────────────────────────────────────────────
            island.console("islandadmin up Ada 1h");
            Await.until("held up, it loads at once", WAIT, () -> info(island, "Ada").contains("loaded: true"));
            assertThat(info(island, "Ada")).contains("held: up");
            island.console("islandadmin release Ada");
            Await.until("let go, it sleeps again", WAIT, () -> info(island, "Ada").contains("loaded: false"));
            island.console("islandadmin down Ada forever");
            ada.run("island");
            Await.until("Ada is on the island server again", WAIT, () -> island.console("list").contains("Ada"));
            ada.expectChat("closed for now");
            assertThat(info(island, "Ada")).contains("loaded: false");
            island.console("islandadmin release Ada");

            // ── auto-unload off: up, and still up after a restart ──────────────────────────────────────────────────
            ada.run("server smp");
            Await.until("Ada left", WAIT, () -> !island.console("list").contains("Ada"));
            island.console("islandadmin autounload Ada off");
            Await.until("auto-unload off loads it", WAIT, () -> info(island, "Ada").contains("loaded: true"));
            island.restart();
            island.awaitLog("This is the island server", WAIT);
            Await.until("after the restart it is up again by itself", WAIT, () -> info(island, "Ada").contains("loaded: true"));
            assertThat(info(island, "Ada")).contains("auto-unload: false");
            Await.until("its shards survived the restart", WAIT, () -> island.console("islandadmin list").contains("Ada"));

            assertThat(errors(island)).isEmpty();
            assertThat(errors(smp)).isEmpty();
        } finally {
            smp.keepLogsIn(E2e.logsFolder("oneblock-net-smp"));
            island.keepLogsIn(E2e.logsFolder("oneblock-net-island"));
        }
    }

    private static String info(PaperServer island, String player) {
        return island.console("islandadmin info " + player);
    }

    /** Both servers run on one machine here, so only one of them gets Core's pack port — that one line is the test's. */
    private static List<String> errors(PaperServer server) {
        return server.errorsFrom("RainsOneBlock", "RainsCore").stream()
                .filter(line -> !line.contains("resource pack server could not start")).toList();
    }

    static Path jar(String module, String pattern) {
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

    static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
