package de.raindancer.e2e.speedrun;

import de.raindancer.e2e.Await;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A server coming from the old jars, on a real server: RainsManhunt's own folder moved into
 * RainsSpeedrun's (byte-identical backup kept, a note left behind, ratings intact), and speedrun's own
 * history.yml moved into Core's run history (backed up, then gone) — and a second start moves nothing
 * twice.
 */
@Tag("e2e")
class MigrationScenarioTest {

    private static final String OLD_STATS = """
            players:
              11111111-1111-1111-1111-111111111111:
                name: Anna
                rating: 1043.5
                hunts: 3
                runner-hunts: 2
                runner-wins: 1
                hunter-hunts: 1
                hunter-wins: 1
                catches: 2
                deaths: 1
                times-caught: 1
                survived-millis: 900000
                best-survival-millis: 600000
                distance: 1234.5
                portals: 4
            """;

    private static final String OLD_HISTORY = """
            runs:
              run-one:
                category: minecraft:end/kill_dragon|RANDOM||
                started: 1790000000000
                millis: 1260000
                outcome: advancement:minecraft:end/kill_dragon
                completed: true
                seed: 42
                players:
                  22222222-2222-2222-2222-222222222222: Alice
                timeline:
                - kind: SPLIT
                  at: 240000
                  who: 22222222-2222-2222-2222-222222222222
                  detail: enter-nether
                - kind: FINISH
                  at: 1260000
                  who: ''
                  detail: advancement:minecraft:end/kill_dragon
                labels: {}
                winner: ''
                results: {}
            """;

    @Test
    @DisplayName("RainsManhunt's files and the old history.yml move in on the first start, backed up, and only once")
    @Covers({"behaviour:migration:manhunt-files", "behaviour:migration:history-to-core"})
    void migrates() throws IOException {
        try (Game game = Game.start("migration", Map.of(), Map.of(
                "plugins/RainsManhunt/stats.yml", OLD_STATS,
                Game.DATA + "history.yml", OLD_HISTORY))) {
            var server = game.server;
            assertThat(server.file("plugins/RainsManhunt/stats.yml")).doesNotExist();
            assertThat(server.file("plugins/RainsManhunt/MOVED-TO-RAINSSPEEDRUN.txt")).exists();
            assertThat(Files.readString(server.file(Game.DATA + "backup/RainsManhunt/stats.yml"))).isEqualTo(OLD_STATS);
            assertThat(server.console("manhunt stats Anna")).contains("rating 1044");
            Game.covered("behaviour:migration:manhunt-files");

            Await.until("history.yml was moved into Core's history", Duration.ofSeconds(30),
                    () -> !Files.exists(server.file(Game.DATA + "history.yml")));
            assertThat(Files.readString(server.file(Game.DATA + "backup/history.yml"))).isEqualTo(OLD_HISTORY);
            server.awaitLog("Moved 1 run\\(s\\) of the old speedrun history", Duration.ofSeconds(10));
            Game.covered("behaviour:migration:history-to-core");

            server.restart();
            server.awaitLog("1 past run\\(s\\) in the speedrun history", Duration.ofSeconds(30));
            assertThat(server.logLines(line -> line.contains("Moved") && line.contains("old speedrun history"))).isEmpty();
            assertThat(server.console("manhunt stats Anna")).contains("rating 1044");
        }
    }
}
