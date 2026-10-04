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
 * A plain race, start to finish, as two players see it: the lobby items, the pre-flight page, the
 * countdown that holds everybody in place, the clean slate and the kit, the sidebar with the clock and
 * the splits, the goal ending the run, and the lobby ready again with a fresh world.
 */
@Tag("e2e")
class RaceScenarioTest {

    @Test
    @DisplayName("a plain race: lobby items, countdown freeze, clean slate, kit, sidebar, goal, and a fresh lobby")
    @Covers({"behaviour:race:clean-slate", "behaviour:race:practice-kit", "behaviour:race:hud",
            "behaviour:race:countdown-freeze", "behaviour:race:goal-ends-run",
            "state:READY", "state:COUNTDOWN", "state:RUNNING", "state:FINISHED",
            "transition:READY->COUNTDOWN", "transition:COUNTDOWN->RUNNING", "transition:RUNNING->FINISHED",
            "transition:FINISHED->READY", "word:speedrun:start", "menu:SpeedrunPreflightMenu"})
    void plainRace() {
        try (Game game = Game.start("race", Map.of(
                "practice-kit", "EYES_OF_ENDER",
                "advancement-key", "minecraft:story/mine_stone",
                "restart-after-seconds", "5"))) {
            Bot ada = game.admin("Ada");
            Bot bo = game.player("Bo");
            Game.awaitLobbyItems(ada);
            Game.awaitLobbyItems(bo);
            Game.covered("state:READY");
            game.server.console("give Bo minecraft:diamond 5");
            bo.expectItem("the diamonds", item -> item.is("DIAMOND"));

            Game.startRun(ada);
            ada.expectBossBar("");
            Game.covered("state:COUNTDOWN", "transition:READY->COUNTDOWN");
            var before = bo.position();
            bo.step(3, 0);
            Await.until("Bo is held in his block during the countdown", Duration.ofSeconds(5),
                    () -> Math.floor(bo.position().getX()) == Math.floor(before.getX()));
            Game.covered("behaviour:race:countdown-freeze");

            Game.awaitRacing(ada);
            Game.awaitRacing(bo);
            Game.covered("state:RUNNING", "transition:COUNTDOWN->RUNNING", "behaviour:race:hud");
            bo.expectNoItem("the diamonds from before the run", item -> item.is("DIAMOND"));
            assertThat(bo.gameMode()).isEqualTo(GameMode.SURVIVAL);
            Game.covered("behaviour:race:clean-slate");
            Bot.Item eyes = bo.expectItem("the practice kit's eyes of ender", item -> item.is("ENDER_EYE"));
            assertThat(eyes.amount()).isEqualTo(14);
            Game.covered("behaviour:race:practice-kit");

            game.server.console("advancement grant Ada only minecraft:story/mine_stone");
            ada.expectChat("Run finished!");
            // A practice run ranks too — on the practice board, never beside real runs.
            assertThat(ada.expectChat("New server record").text()).contains("Practice");
            Game.covered("state:FINISHED", "transition:RUNNING->FINISHED", "behaviour:race:goal-ends-run");

            Await.until("the lobby is remade and ready, items back", Duration.ofSeconds(120), () ->
                    bo.carrying(item -> item.tag("speedrun-lobby-item").isPresent()).isPresent());
            Game.covered("transition:FINISHED->READY");
            assertThat(game.server.errorsFrom("RainsSpeedrun", "RainsCore")).isEmpty();
        }
    }
}
