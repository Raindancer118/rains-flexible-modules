package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The anti-ESP shield on a real server: players behind walls are hidden from each other, and stay listed. */
@Tag("e2e")
class AntiCheatShieldScenarioTest {

    @Test
    @DisplayName("players behind walls are hidden but stay listed")
    void shield() {
        try (Server server = Server.start("anticheat-shield",
                List.of("moderation-standalone:RainsModeration-.*"), List.of("Anti-cheat is up"))) {
            server.console("forceload add -32 -32 32 32");
            server.console("fill -24 100 -24 24 100 24 minecraft:stone");
            server.console("fill -24 101 -24 24 120 24 minecraft:air");

            Bot seer = server.player("Seer");
            Bot hider = server.player("Hider");
            server.console("tp Seer 0.5 101 -8.5");
            server.console("tp Hider 0.5 101 8.5");
            Await.ticks(80);

            assertThat(server.console("settings set anti-esp true")).contains("is now");
            Await.until("Seer sees Hider in the open", Duration.ofSeconds(10), () -> seer.sees(hider.id()));
            server.console("fill -6 101 0 6 106 0 minecraft:stone");
            Await.until("the wall hides Hider from Seer", Duration.ofSeconds(10), () -> !seer.sees(hider.id()));
            assertThat(seer.unlisted()).as("hidden, but still in the tab list").doesNotContain(hider.id());
            server.console("fill -6 101 0 6 106 0 minecraft:air");
            Await.until("Hider is back once the wall is gone", Duration.ofSeconds(10), () -> seer.sees(hider.id()));

            assertThat(server.paper.errorsFrom("RainsCore", "RainsModeration")).isEmpty();
        }
    }
}
