package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import de.raindancer.e2e.Downloads;
import de.raindancer.e2e.E2e;
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
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OneBlock as Lilly's network will run it: a proxy, a survival server with RainsOneBlock and the island server
 * with BentoBox, AOneBlock and RainsOneBlock — linked as a peer of the survival server's link.yml.
 *
 * <p>What is proved here and nowhere else: {@code /island} on the survival server lands a player on their island;
 * an invite typed on the island server reaches a player on the survival server with working buttons; accepting moves
 * them over and BentoBox's own accept puts them in the team; a guest invite takes the guest to the island.
 */
@Tag("e2e")
class OneBlockNetworkScenarioTest {

    private static final Duration WAIT = Duration.ofSeconds(90);
    private static final String LINK_SECRET = "e2e-link-secret-0123456789abcdef";
    private static final String FORWARDING_SECRET = "e2e-forwarding-secret";

    @Test
    @DisplayName("to the island from the survival server, a team invite and a guest invite across the network")
    void network() throws Exception {
        int linkPort = freePort();
        String paperGlobal = VelocityProxy.paperGlobal(FORWARDING_SECRET);
        Path bentoBox = Downloads.pinned("BentoBox-3.23.3.jar",
                "https://cdn.modrinth.com/data/aBVLHiAW/versions/Wm9asg4I/BentoBox-3.23.3.jar",
                "c2496d2a8978fbd06a012b406d6787b9a6506c88");
        Path aOneBlock = Downloads.pinned("AOneBlock-1.28.0.jar",
                "https://cdn.modrinth.com/data/qq7CK8U4/versions/SO3SQFxw/AOneBlock-1.28.0.jar",
                "f5aea03a4e738a6bcd3d9e088bd0e657f5500918");

        PaperServer smp = PaperServer.forRun().seed(1).in(E2e.serverFolder("oneblock-net-smp"))
                .plugin(Path.of(System.getProperty("e2e.rainscore")))
                .plugin(jar("oneblock-standalone", "RainsOneBlock-.*"))
                .file("config/paper-global.yml", paperGlobal)
                .file("plugins/RainsCore/link.yml", "role: off\nserver-name: SMP\npeers:\n  oneblock:\n"
                        + "    role: guest\n    host-url: http://127.0.0.1:" + linkPort + "\n    secret: " + LINK_SECRET + "\n")
                .build();
        PaperServer island = PaperServer.forRun().seed(1).in(E2e.serverFolder("oneblock-net-island"))
                .plugin(Path.of(System.getProperty("e2e.rainscore")))
                .plugin(bentoBox)
                .plugin(jar("oneblock-standalone", "RainsOneBlock-.*"))
                .file("config/paper-global.yml", paperGlobal)
                .file("plugins/RainsCore/link.yml", "role: host\nlisten-address: 127.0.0.1\nlisten-port: " + linkPort
                        + "\nsecret: " + LINK_SECRET + "\nserver-name: OneBlock\nfollows: true\n")
                .build();
        // Bots cannot click Paper's dialogs, which BentoBox confirms with by default; typed confirmation is the same accept.
        Path bentoConfig = island.folder().resolve("plugins").resolve("BentoBox").resolve("config.yml");
        Files.createDirectories(bentoConfig.getParent());
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(bentoBox.toFile())) {
            String defaults = new String(zip.getInputStream(zip.getEntry("config.yml")).readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertThat(defaults).contains("    confirmations: true");
            Files.writeString(bentoConfig, defaults.replace("    confirmations: true", "    confirmations: false"));
        }
        Path addons = island.folder().resolve("plugins").resolve("BentoBox").resolve("addons");
        Files.createDirectories(addons);
        Files.copy(aOneBlock, addons.resolve(aOneBlock.getFileName()), StandardCopyOption.REPLACE_EXISTING);

        Map<String, Integer> servers = new LinkedHashMap<>();
        servers.put("smp", smp.port());
        servers.put("oneblock", island.port());
        try (VelocityProxy proxy = VelocityProxy.in(E2e.serverFolder("oneblock-net-proxy"), servers, FORWARDING_SECRET);
             smp; island) {
            island.start();
            island.awaitLog("Server link: listening", WAIT);
            island.awaitLog("This is the island server", WAIT);
            smp.start();
            proxy.start();

            // ── /island on the survival server: through the proxy, and BentoBox makes the island ──────────────
            Bot ada = smp.bot("Ada").through(proxy.port()).join();
            ada.run("island");
            Await.until("Ada is on the island server", WAIT, () -> island.console("list").contains("Ada"));
            try {
                Await.until("Ada has an island", WAIT, () -> hasIsland(island, "Ada"));
            } catch (AssertionError none) {
                throw new AssertionError(none.getMessage() + " — Ada was told " + ada.chatText() + "; obadmin info: "
                        + island.console("obadmin info Ada") + "; ob as Ada: " + island.console("execute as Ada run ob"), none);
            }
            Await.until("Ada stands on it", WAIT, () -> ada.world().contains("oneblock"));

            // ── the sidebar: her island, her rank, the phase with its bar ─────────────────────────────────────
            ada.expectSidebar("Your island");
            ada.expectSidebar("You: Owner");
            ada.expectSidebar("Plains");
            ada.expectSidebar("blocks broken");
            assertThat(ada.sidebar()).anyMatch(line -> line.contains("%"));
            ada.expectSidebar("Next: ");

            // ── the block breaks, the count goes up ──────────────────────────────────────────────────────────
            island.console("gamemode creative Ada");
            island.console("execute in minecraft:oneblock_world run tp Ada 1.5 81 0.5 90 45");
            Await.ticks(20);
            try {
                // Digs until the count shows on the sidebar; it redraws every two seconds, so it is past 1 by then.
            Await.until("a block broken shows on the sidebar", WAIT, () -> {
                    ada.dig(0, 80, 0);
                    Await.ticks(10);
                return ada.sidebar().stream().anyMatch(line -> line.matches(".*\\b[1-9][0-9,]* blocks broken.*"));
                });
            } catch (AssertionError none) {
                throw new AssertionError(none.getMessage() + " — Ada at " + island.console("data get entity Ada Pos")
                        + " in " + ada.world() + "; block 0 80 0: " + island.console("execute in minecraft:oneblock_world if block 0 80 0 minecraft:air")
                        + "; sidebar " + ada.sidebar(), none);
            }
            island.console("gamemode survival Ada");

            // ── a team invite typed on the island server, read on the survival server ─────────────────────────
            Bot bo = smp.bot("Bo").through(proxy.port()).join();
            Await.until("the island server knows Bo is on the survival server", WAIT, () -> {
                ada.run("island invite Bo");
                return ada.chatText().stream().anyMatch(line -> line.contains("Invited Bo to your team"));
            });
            bo.expectChat("wants you on their OneBlock team");
            assertThat(bo.clicksOn("wants you on their OneBlock team")).anyMatch(click -> click.contains("island accept Ada"));

            // ── Bo clicks Join: over to the island server, where BentoBox's own accept runs ───────────────────
            bo.clickButtonOn("wants you on their OneBlock team", 0);
            Await.until("Bo is on the island server", WAIT, () -> island.console("list").contains("Bo"));
            ada.expectChat("Bo said yes");
            try {
                Await.until("Bo is on Ada's team", WAIT, () -> {
                    if (!onTeamOf(island, "Bo", "Ada")) {
                        // BentoBox asks a player who joins a team to confirm by typing the accept again.
                        bo.run("ob team accept");
                    }
                    return onTeamOf(island, "Bo", "Ada");
                });
            } catch (AssertionError none) {
                throw new AssertionError(none.getMessage() + " — Bo was told " + bo.chatText() + "; obadmin info Bo: "
                        + island.console("obadmin info Bo") + "; obadmin info Ada: " + island.console("obadmin info Ada"), none);
            }

            Await.until("Bo's sidebar has him as a member", WAIT, () -> bo.sidebar().stream().anyMatch(line -> line.contains("You: Member")));

            // ── refusals are said, and say why ────────────────────────────────────────────────────────────
            ada.run("island invite Bo");
            ada.expectChat("already on your island");
            ada.run("island invite Ghost");
            ada.expectChat("Nobody called Ghost");

            // ── a guest invite: Cy is taken to Ada's island and is no member of it ────────────────────────────
            Bot cy = smp.bot("Cy").through(proxy.port()).join();
            Await.until("the island server knows Cy is on the survival server", WAIT, () -> {
                ada.run("island guest Cy");
                return ada.chatText().stream().anyMatch(line -> line.contains("Invited Cy to visit"));
            });
            cy.clickButtonOn("look round their OneBlock island", 0);
            Await.until("Cy is on the island server", WAIT, () -> island.console("list").contains("Cy"));
            Await.until("Cy is at Ada's island", WAIT, () -> island.console(
                    "execute at Ada if entity @a[name=Cy,distance=..40]").contains("passed"));
            assertThat(onTeamOf(island, "Cy", "Ada")).isFalse();
            Await.until("Cy's sidebar: Ada's island, as a visitor", WAIT, () -> cy.sidebar().stream().anyMatch(line -> line.contains("Ada's island"))
                    && cy.sidebar().stream().anyMatch(line -> line.contains("You: Visitor")));

            // ── declining, on the island server itself ────────────────────────────────────────────────────────
            ada.run("island invite Cy");
            cy.expectChat("wants you on their OneBlock team");
            cy.run("island deny");
            ada.expectChat("Cy turned your invite down");

            // ── and whoever does not want it hides it ──────────────────────────────────────────────────────────
            ada.run("island sidebar");
            ada.expectChat("Sidebar off");
            Await.until("Ada's sidebar is gone", WAIT, () -> ada.sidebar().isEmpty());

            assertThat(errors(island)).isEmpty();
            assertThat(errors(smp)).isEmpty();
        } finally {
            smp.keepLogsIn(E2e.logsFolder("oneblock-net-smp"));
            island.keepLogsIn(E2e.logsFolder("oneblock-net-island"));
        }
    }

    /** Both servers run on one machine here, so only one of them gets Core's pack port — that one line is the test's. */
    private static java.util.List<String> errors(PaperServer server) {
        return server.errorsFrom("RainsOneBlock", "RainsCore", "BentoBox").stream()
                .filter(line -> !line.contains("resource pack server could not start")).toList();
    }

    private static boolean hasIsland(PaperServer island, String player) {
        String info = island.console("obadmin info " + player);
        return info.contains("Owner") || info.contains("owner") || info.contains("Island UUID");
    }

    private static boolean onTeamOf(PaperServer island, String player, String owner) {
        String info = island.console("obadmin info " + player);
        return info.contains("Owner: " + owner) || (info.contains(owner) && info.contains("Team members"));
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

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
