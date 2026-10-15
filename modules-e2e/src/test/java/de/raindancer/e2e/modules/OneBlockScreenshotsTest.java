package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import de.raindancer.e2e.E2e;
import de.raindancer.e2e.PaperServer;
import de.raindancer.e2e.RealClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OneBlock pages photographed with the real client:
 * {@code mvn -o -Pe2e test -pl modules-e2e -De2e.groups=screenshots -Dtest=OneBlockScreenshotsTest}.
 *
 * <p>The real client cannot be scripted, so a bot opens the same page first and says which slot a button landed on
 * — the menus centre their bands, so a slot is read, never worked out.
 */
@Tag("screenshots")
class OneBlockScreenshotsTest {

    private static final Duration WAIT = Duration.ofSeconds(90);

    @Test
    @DisplayName("photographed with the real client")
    void screenshots() {
        PaperServer server = PaperServer.forRun().seed(1).in(E2e.serverFolder("oneblock-screenshots"))
                .plugin(Path.of(System.getProperty("e2e.rainscore")))
                .plugin(OneBlockNetworkScenarioTest.jar("oneblock-standalone", "RainsOneBlock-.*"))
                .file("plugins/RainsOneBlock/config.yml", "hosts-islands: true\n")
                .build();
        Path shots = E2e.out().resolve("screenshots");
        try (server) {
            server.start();
            Bot scout = server.bot("Scout").join();
            int pure = slot(scout, "Pure OneBlock");
            scout.clickSlot(pure);
            int yes = slot(scout, "Yes, do it");

            try (RealClient viewer = RealClient.join(server, "Viewer")) {
                Await.ticks(100);
                viewer.screenshot(shots.resolve("oneblock-1-choose-money.png"));
                viewer.screenshotHovering(shots.resolve("oneblock-2-choose-money-hover.png"), pure);
                click(server, pure);
                Await.ticks(30);
                viewer.screenshot(shots.resolve("oneblock-3-confirm.png"));
                click(server, yes);
                Await.ticks(100);
                server.console("time set day");
                // Floating back from the block and looking down at it, so the picture shows the island rather than the
                // sky — as a spectator, since a step back on a one-block island is a step into the void.
                server.console("gamemode spectator Viewer");
                server.console("execute as Viewer at @s run tp @s ~ ~1.5 ~3.5 180 30");
                Await.ticks(60);
                viewer.screenshot(shots.resolve("oneblock-4-on-the-island.png"));
                server.console("gamemode creative Viewer");
                server.runAs("Viewer", "island home");
                Await.ticks(40);

                server.console("islandadmin shards Viewer 650");
                server.runAs("Viewer", "island menu");
                Await.ticks(40);
                viewer.screenshot(shots.resolve("oneblock-5-island-menu.png"));
                scout.closeWindow();
                server.console("islandadmin shards Scout 650");
                scout.run("island create");
                Await.ticks(20);

                server.runAs("Viewer", "island upgrades");
                Await.ticks(40);
                // The first upgrade, twice: a bought one wears its level and a glint.
                click(server, 0);
                Await.ticks(20);
                click(server, 0);
                Await.ticks(30);
                viewer.screenshot(shots.resolve("oneblock-6-upgrades.png"));
                viewer.screenshotHovering(shots.resolve("oneblock-6b-upgrade-tooltip.png"), 0);
                server.runAs("Viewer", "island team");
                Await.ticks(40);
                viewer.screenshot(shots.resolve("oneblock-7-team.png"));

                server.console("op Viewer");
                Await.ticks(20);
                server.runAs("Viewer", "islandadmin up Viewer 1d");
                server.runAs("Viewer", "islandadmin list");
                Await.ticks(40);
                viewer.screenshot(shots.resolve("oneblock-8-admin-list.png"));
                server.runAs("Viewer", "islandadmin info Viewer");
                Await.ticks(40);
                viewer.screenshot(shots.resolve("oneblock-9-admin-island.png"));
                assertThat(shots.resolve("oneblock-9-admin-island.png")).exists();
            }
        } finally {
            server.keepLogsIn(E2e.logsFolder("oneblock-screenshots"));
        }
    }

    private static int slot(Bot bot, String label) {
        Supplier<Integer> found = () -> bot.window().flatMap(window -> window.slotNamed(label)).orElse(null);
        Await.until("a page with '" + label + "' is open for " + bot.name(), WAIT, () -> found.get() != null);
        return found.get();
    }

    private static void click(PaperServer server, int slot) {
        server.console("e2e click Viewer " + slot);
    }
}
