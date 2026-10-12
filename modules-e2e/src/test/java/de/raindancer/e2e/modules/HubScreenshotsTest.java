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

/** {@code /hub} photographed with the real client: {@code mvn -o -Pscreenshots test -pl modules-e2e -Dtest=HubScreenshotsTest}. */
@Tag("screenshots")
class HubScreenshotsTest {

    @Test
    @DisplayName("photographed with the real client")
    void screenshots() {
        try (Server server = Server.start("hub-screenshots", HubScenarioTest.PLUGINS, List.of())) {
            Await.ticks(60);
            try (RealClient viewer = RealClient.join(server.paper, "Viewer")) {
                Path shots = E2e.out().resolve("screenshots");
                server.console("e2e as Viewer hub");
                Await.ticks(60);
                viewer.screenshot(shots.resolve("hub-1-player.png"));
                viewer.screenshotHovering(shots.resolve("hub-2-door-tooltip.png"), 10);
                server.console("op Viewer");
                Await.ticks(20);
                server.console("e2e as Viewer hub");
                Await.ticks(60);
                viewer.screenshot(shots.resolve("hub-3-op.png"));
                server.console("e2e as Viewer hub economy");
                Await.ticks(60);
                viewer.screenshot(shots.resolve("hub-4-module-page.png"));
                assertThat(shots.resolve("hub-4-module-page.png")).exists();
            }
        }
    }
}
