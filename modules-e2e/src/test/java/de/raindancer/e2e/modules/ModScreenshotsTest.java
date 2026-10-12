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
 * The player screen behind {@code /mod <player>}, photographed with the real game: the hub with its three
 * doors, and each room behind them.
 * {@code mvn -o -Pscreenshots test -pl modules-e2e -Dtest=ModScreenshotsTest}; pictures under target/e2e/screenshots.
 */
@Tag("screenshots")
class ModScreenshotsTest {

    @Test
    @DisplayName("/mod: the hub and its three rooms")
    void screenshots() {
        try (Server server = Server.start("mod-screenshots", List.of("moderation-standalone:RainsModeration-.*"),
                List.of("Moderation is up"))) {
            server.player("Bo");
            try (RealClient viewer = RealClient.join(server.paper, "Viewer")) {
                server.console("op Viewer");
                Path shots = E2e.out().resolve("screenshots");
                List<Path> taken = new java.util.ArrayList<>();
                taken.add(page(server, viewer, null, shots.resolve("mod-hub.png")));
                taken.add(page(server, viewer, 20, shots.resolve("mod-look-into.png")));
                taken.add(page(server, viewer, 22, shots.resolve("mod-punish.png")));
                taken.add(page(server, viewer, 24, shots.resolve("mod-practical.png")));
                assertThat(taken).allSatisfy(path -> assertThat(path).exists());
                assertThat(server.paper.errorsFrom("RainsCore", "RainsModeration")).isEmpty();
            }
        }
    }

    private static Path page(Server server, RealClient viewer, Integer door, Path target) {
        server.console("e2e as Viewer mod Bo");
        Await.ticks(40);
        if (door != null) {
            server.console("e2e click Viewer " + door);
            Await.ticks(40);
        }
        Path shot = viewer.screenshot(target);
        server.console("e2e as Viewer minecraft:tell Viewer .");
        return shot;
    }
}
