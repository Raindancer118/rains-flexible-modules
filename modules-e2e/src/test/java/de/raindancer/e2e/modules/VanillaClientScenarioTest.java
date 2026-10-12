package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.RealClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real, unmodified client against the anti-cheat: standing, being teleported and standing again must
 * leave no record. The bots only send what a test tells them; this is what Mojang's client actually sends
 * — the position reminder Blink relies on above all.
 * {@code mvn -o -Pscreenshots test -pl modules-e2e -Dtest=VanillaClientScenarioTest}
 */
@Tag("screenshots")
class VanillaClientScenarioTest {

    @Test
    @DisplayName("an idle vanilla client, teleported now and then, is squeaky clean")
    void idleIsClean() {
        try (Server server = Server.start("vanilla-client", List.of("anticheat-standalone:RainsAntiCheat-.*"),
                List.of("Anti-cheat is up"))) {
            try (RealClient viewer = RealClient.join(server.paper, "Viewer")) {
                server.console("gamemode survival Viewer");
                for (int round = 0; round < 3; round++) {
                    server.console("tp Viewer " + (round * 5) + " 100 0");
                    server.console("fill " + (round * 5 - 2) + " 99 -2 " + (round * 5 + 2) + " 99 2 minecraft:stone");
                    Await.ticks(200);
                }
                assertThat(server.console("anticheat info Viewer")).contains("Squeaky clean");
                assertThat(server.console("anticheat log Viewer")).contains("No evidence");
            }
        }
    }
}
