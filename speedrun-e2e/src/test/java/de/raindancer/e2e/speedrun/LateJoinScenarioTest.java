package de.raindancer.e2e.speedrun;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Somebody joining a run under way, in each {@code late-join} mode, in a plain race and in a hunt —
 * what they get, what the racers are told, and the door a hunt can close in front of them.
 */
@Tag("e2e")
class LateJoinScenarioTest {

    /** A latecomer who looks on: nothing of the run is theirs. */
    private static void looksOn(Bot late, Bot racer) {
        late.expectWorld("speedrun");
        late.expectNoChat("You join the run", Duration.ofSeconds(3));
        assertThat(late.gameMode()).isEqualTo(GameMode.SURVIVAL);
        assertThat(racer.chatText()).noneMatch(line -> line.contains(late.name() + " joined the run"));
    }

    /** A latecomer who watches: spectator mode, told why. */
    private static void watches(Bot late) {
        late.expectChat("you are watching");
        late.expectGameMode(GameMode.SPECTATOR);
    }

    @Test
    @DisplayName("a race: OFF looks on, SPECTATE watches until it is over, RACE races with the kit and the clock")
    @Covers({"behaviour:latejoin:speedrun:OFF", "behaviour:latejoin:speedrun:SPECTATE",
            "behaviour:latejoin:speedrun:RACE", "setting:late-join", "setting:practice-kit"})
    void race() {
        try (Game game = Game.start("latejoin-race", Map.of("advancement-key", "minecraft:story/mine_stone",
                "restart-when-run-ends", "false"))) {
            Bot ada = game.admin("Ada");
            Game.awaitLobbyItems(ada);
            game.set("practice-kit", "EYES_OF_ENDER");
            Game.startRun(ada);
            Game.awaitRacing(ada);

            game.set("late-join", "OFF");
            Bot off = game.player("Olga");
            looksOn(off, ada);
            Game.covered("behaviour:latejoin:speedrun:OFF");

            game.set("late-join", "SPECTATE");
            Bot spec = game.player("Sam");
            watches(spec);
            Game.covered("behaviour:latejoin:speedrun:SPECTATE", "setting:late-join");

            game.set("late-join", "RACE");
            Bot late = game.player("Rita");
            late.expectChat("You join the run at");
            ada.expectChat("Rita joined the run");
            late.expectWorld("speedrun");
            late.expectGameMode(GameMode.SURVIVAL);
            assertThat(late.expectItem("the practice kit", item -> item.is("ENDER_EYE")).amount()).isEqualTo(14);
            Game.covered("setting:practice-kit");
            Await.until("Rita sees the run's sidebar", Duration.ofSeconds(15), () -> !late.sidebar().isEmpty());
            Game.covered("behaviour:latejoin:speedrun:RACE");

            // Over: the watcher stands up; the late racer is told the run is no best of theirs.
            game.server.console("advancement grant Ada only minecraft:story/mine_stone");
            spec.expectGameMode(GameMode.SURVIVAL);
            late.expectChat("you joined it after the start");
        }
    }

    @Test
    @DisplayName("a hunt: a latecomer hunts by default with a Hunter's compass, runs when the server says so, and a closed door keeps strangers out")
    @Covers({"behaviour:latejoin:manhunt:OFF", "behaviour:latejoin:manhunt:SPECTATE",
            "behaviour:latejoin:manhunt:RACE", "setting:late-joiner-side", "setting:close-whitelist-on-start"})
    void hunt() {
        try (Game game = Game.start("latejoin-hunt", Map.of("game-mode", "manhunt"))) {
            Bot ada = game.admin("Ada");
            Bot bo = game.player("Bo");
            Game.awaitLobbyItems(bo);
            bo.run("manhunt join runner");
            bo.expectChat("You are running");
            Game.startRun(ada);
            Game.awaitRacing(bo);

            game.set("late-join", "OFF");
            Bot off = game.player("Olga");
            looksOn(off, ada);
            assertThat(off.carrying(ManhuntScenarioTest.TRACKER)).isEmpty();
            Game.covered("behaviour:latejoin:manhunt:OFF");

            game.set("late-join", "SPECTATE");
            watches(game.player("Sam"));
            Game.covered("behaviour:latejoin:manhunt:SPECTATE");

            game.set("late-join", "RACE");
            Bot hunter = game.player("Hal");
            hunter.expectChat("You join the run at");
            hunter.expectChat("You are hunting");
            hunter.expectItem("a Hunter's tracking compass", ManhuntScenarioTest.TRACKER);
            Game.covered("behaviour:latejoin:manhunt:RACE");

            game.set("late-joiner-side", "RUNNER");
            Bot runner = game.player("Rue");
            runner.expectChat("You are running");
            Await.never("Rue is handed a Hunter's tracker", Duration.ofSeconds(3),
                    () -> runner.carrying(ManhuntScenarioTest.TRACKER).isPresent());
            Game.covered("setting:late-joiner-side");

            // A hunt that shuts the door: nobody new gets in at all, whatever late-join says.
            game.set("close-whitelist-on-start", "true");
            game.server.console("speedrunreset confirm");
            Await.until("the lobby is ready again", Duration.ofSeconds(120),
                    () -> bo.carrying(item -> item.tag("speedrun-lobby-item").isPresent()).isPresent());
            bo.run("manhunt join runner");
            bo.expectChat("You are running");
            Game.startRun(ada);
            Game.awaitRacing(bo);
            String refused = game.server.bot("Stranger").joinRefused();
            assertThat(refused).isNotBlank();
            Game.covered("setting:close-whitelist-on-start");
        }
    }
}
