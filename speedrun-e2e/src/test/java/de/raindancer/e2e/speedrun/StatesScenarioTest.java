package de.raindancer.e2e.speedrun;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every way the lobby moves between its states that a race does not already take: a racer who leaves
 * and comes back keeps everything (and a run with nobody online pauses), a run reset mid-way, a
 * countdown refused at zero, a run resumed after a restart with everybody where they stood, and one
 * resumed over a finished run.
 */
@Tag("e2e")
class StatesScenarioTest {

    /** The hub's state, as somebody not racing reads it. */
    private static boolean hubSays(Bot staff, String text) {
        staff.run("speedrun menu");
        Bot.Window hub = staff.awaitWindow("Speedrun");
        boolean says = hub.title().contains(text) || hub.top().values().stream()
                .anyMatch(item -> item.name().contains(text) || item.lore().stream().anyMatch(line -> line.contains(text)));
        staff.closeWindow();
        return says;
    }

    @Test
    @DisplayName("a racer who reconnects keeps their things; nobody online pauses the run, somebody back resumes it; a reset ends it")
    @Covers({"behaviour:reconnect:keeps-state", "state:PAUSED", "transition:RUNNING->PAUSED",
            "transition:PAUSED->RUNNING", "transition:RUNNING->READY", "word:speedrun:spectate",
            "command:speedrunreset"})
    void reconnectPauseReset() {
        try (Game game = Game.start("states-pause")) {
            Bot watcher = game.admin("Wes");
            Bot bo = game.player("Bo");
            Game.awaitLobbyItems(bo);
            watcher.run("speedrun spectate");
            watcher.expectChat("not racing");
            Game.covered("word:speedrun:spectate");
            Game.startRun(watcher, bo);
            Game.awaitRacing(bo);
            game.server.console("give Bo minecraft:diamond 3");
            bo.expectItem("the diamonds", item -> item.is("DIAMOND"));

            bo.leave();
            Await.until("the hub says the run is paused", Duration.ofSeconds(20), () -> hubSays(watcher, "Paused"));
            Game.covered("state:PAUSED", "transition:RUNNING->PAUSED");

            Bot back = game.server.bot("Bo").join();
            back.expectItem("his diamonds, still", item -> item.is("DIAMOND"));
            back.expectNoChat("You join the run", Duration.ofSeconds(3));
            Await.until("Bo sees the run's sidebar again", Duration.ofSeconds(15), () -> !back.sidebar().isEmpty());
            Game.covered("behaviour:reconnect:keeps-state");
            Await.until("the hub no longer says paused", Duration.ofSeconds(20), () -> !hubSays(watcher, "Paused"));
            Game.covered("transition:PAUSED->RUNNING");

            game.server.console("speedrunreset confirm");
            Await.until("the lobby is ready again", Duration.ofSeconds(120),
                    () -> back.carrying(item -> item.tag("speedrun-lobby-item").isPresent()).isPresent());
            Game.covered("transition:RUNNING->READY", "command:speedrunreset");
            assertThat(game.server.errorsFrom("RainsSpeedrun", "RainsCore")).isEmpty();
        }
    }

    @Test
    @DisplayName("a countdown that can no longer start at zero hands the lobby back, and says why")
    @Covers({"transition:COUNTDOWN->READY"})
    void refusedAtZero() {
        try (Game game = Game.start("states-refused")) {
            Bot ada = game.admin("Ada");
            Game.awaitLobbyItems(ada);
            Game.startRun(ada);
            ada.expectBossBar("");
            game.set("game-mode", "nope");
            ada.expectChat("nope");
            Game.awaitLobbyItems(ada);
            Game.covered("transition:COUNTDOWN->READY");
        }
    }

    @Test
    @DisplayName("after a restart a run is resumed where everybody stands, at the time typed; and a finished one can be resumed too")
    @Covers({"behaviour:resume:after-restart", "transition:READY->RUNNING", "transition:FINISHED->RUNNING",
            "word:speedrun:resume", "command:speedrunresume", "word:speedrun:time", "command:speedruntime"})
    void resume() {
        try (Game game = Game.start("states-resume", Map.of("advancement-key", "minecraft:story/mine_stone",
                "restart-when-run-ends", "false"))) {
            Bot ada = game.admin("Ada");
            Bot bo = game.player("Bo");
            Game.awaitLobbyItems(bo);
            Game.startRun(ada);
            Game.awaitRacing(bo);
            game.server.console("give Bo minecraft:diamond 2");
            bo.expectItem("diamonds", item -> item.is("DIAMOND"));

            game.server.restart();
            Bot ada2 = game.server.bot("Ada").join();
            Bot bo2 = game.server.bot("Bo").join();
            bo2.expectWorld("speedrun");
            bo2.expectItem("his diamonds, after the restart", item -> item.is("DIAMOND"));
            ada2.run("speedrun resume 1:00");
            ada2.expectChat("resumed at 1:00");
            Await.until("Bo races again", Duration.ofSeconds(15), () -> !bo2.sidebar().isEmpty());
            assertThat(bo2.carrying(item -> item.is("DIAMOND"))).as("nothing was cleared").isPresent();
            Game.covered("behaviour:resume:after-restart", "transition:READY->RUNNING", "word:speedrun:resume");

            ada2.run("speedrun time 2:00");
            ada2.expectChat("now reads 2:00");
            Game.covered("word:speedrun:time");
            ada2.run("speedruntime 3:00");
            ada2.expectChat("now reads 3:00");
            Game.covered("command:speedruntime");

            game.server.console("advancement grant Bo only minecraft:story/mine_stone");
            bo2.expectChat("Run finished!");
            ada2.run("speedrunresume 4:00");
            ada2.expectChat("resumed at 4:00");
            Game.covered("transition:FINISHED->RUNNING", "command:speedrunresume");
        }
    }
}
