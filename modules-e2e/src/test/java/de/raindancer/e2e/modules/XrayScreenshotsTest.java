package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.E2e;
import de.raindancer.e2e.RealClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an x-ray user sees of the bait layer, taken with the real, unmodified game: the probe hides plain
 * rock from this one client the way an x-ray does, so real ore and the bait veins hang in empty space.
 * Then the same view in admin mode, where staff see every bait as a block of redstone.
 * {@code mvn -o -Pe2e test -pl modules-e2e -Dtest=XrayScreenshotsTest}; pictures under target/e2e/screenshots.
 */
@Tag("screenshots")
class XrayScreenshotsTest {

    private static final int X = 40;
    private static final int Y = -40;
    private static final int Z = 40;

    @Test
    @DisplayName("the bait layer through an x-ray, as a player and as staff in admin mode")
    void screenshots() {
        try (Server server = Server.start("xray-screenshots",
                List.of("moderation-standalone:RainsModeration-.*", "essentials-standalone:RainsEssentials-.*"),
                List.of("Moderation is up"))) {
            server.console("forceload add -32 -32 112 112");
            server.console("e2e xray Viewer on");
            try (RealClient viewer = RealClient.join(server.paper, "Viewer", 6)) {
                server.console("op Viewer");
                server.console("gamemode survival Viewer");
                // A pocket to stand in and an invisible floor, so the server keeps them where the picture is.
                server.console("fill " + (X - 1) + " " + Y + " " + (Z - 1) + " " + (X + 1) + " " + (Y + 2) + " " + (Z + 1) + " minecraft:air");
                server.console("setblock " + X + " " + (Y - 1) + " " + Z + " minecraft:barrier");
                look(server);
                Await.ticks(200);
                Path player = viewer.screenshot(E2e.out().resolve("screenshots").resolve("xray-as-player.png"));

                server.console("e2e as Viewer admin");
                Await.ticks(60);
                look(server);
                Await.ticks(120);
                Path staff = viewer.screenshot(E2e.out().resolve("screenshots").resolve("xray-as-staff-in-admin-mode.png"));

                assertThat(player).exists();
                assertThat(staff).exists();
                assertThat(server.paper.errorsFrom("RainsCore", "RainsModeration", "RainsEssentials")).isEmpty();
            }
        }
    }

    private static void look(Server server) {
        server.console("effect give Viewer minecraft:night_vision infinite 0 true");
        server.console("effect give Viewer minecraft:resistance infinite 4 true");
        server.console("tp Viewer " + (X + 0.5) + " " + Y + " " + (Z + 0.5) + " -135 20");
    }
}
