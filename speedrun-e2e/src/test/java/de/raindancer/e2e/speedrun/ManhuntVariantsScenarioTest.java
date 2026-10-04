package de.raindancer.e2e.speedrun;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

/**
 * The rules a hunt can be played by, each seen by the players it applies to: Runner lives, the Hunters'
 * respawn wait, the Runners glowing, the head start growing with the pack, and a Runner who logs out
 * for longer than the grace being caught.
 */
@Tag("e2e")
class ManhuntVariantsScenarioTest {

    private static Game hunt(String scenario, Map<String, String> manhunt) {
        Game game = Game.start(scenario, Map.of("game-mode", "manhunt"));
        manhunt.forEach(game::set);
        return game;
    }

    private static void killAndRespawn(Game game, Bot bot) {
        // Vanilla keeps a player who just respawned safe for three seconds; a second death waits that out.
        Await.ticks(70);
        game.server.console("kill " + bot.name());
        Await.until(bot + " is dead", Duration.ofSeconds(10), bot::isDead);
        bot.respawn();
    }

    @Test
    @DisplayName("lives: a Runner with two loses one, plays on, and is caught by the second death")
    @Covers({"behaviour:variant:lives", "setting:runner-lives"})
    void lives() {
        try (Game game = hunt("variant-lives", Map.of("runner-lives", "2"))) {
            Bot ada = game.admin("Ada");
            Bot bo = game.player("Bo");
            Bot cy = game.player("Cy");
            Game.awaitLobbyItems(bo);
            bo.run("manhunt join runner");
            bo.expectChat("You are running");
            Game.startRun(ada);
            Game.awaitRacing(bo);

            killAndRespawn(game, bo);
            ada.expectChat("lost a life");
            bo.expectGameMode(GameMode.SURVIVAL);
            killAndRespawn(game, bo);
            cy.expectChat("Bo was the last Runner");
            // Caught by his death — the summary says so, not that he stayed away.
            cy.expectChat("Bo died for the last time");
            Game.covered("behaviour:variant:lives", "setting:runner-lives");
        }
    }

    @Test
    @DisplayName("a Hunter who died waits before rejoining the chase, held in place")
    @Covers({"behaviour:variant:respawn-delay", "setting:hunter-respawn-delay-seconds"})
    void respawnDelay() {
        try (Game game = hunt("variant-respawn", Map.of("hunter-respawn-delay-seconds", "8"))) {
            Bot ada = game.admin("Ada");
            Bot bo = game.player("Bo");
            Game.awaitLobbyItems(bo);
            bo.run("manhunt join runner");
            bo.expectChat("You are running");
            Game.startRun(ada);
            Game.awaitRacing(ada);

            killAndRespawn(game, ada);
            ada.expectChat("back in the chase in");
            Await.ticks(20);
            var at = ada.position();
            ada.step(4, 0);
            Await.until("Ada is held where she respawned", Duration.ofSeconds(5),
                    () -> Math.floor(ada.position().getX()) == Math.floor(at.getX()));
            Game.covered("behaviour:variant:respawn-delay", "setting:hunter-respawn-delay-seconds");
        }
    }

    @Test
    @DisplayName("glowing: the Runners are warned, then glow for everybody")
    @Covers({"behaviour:variant:glowing", "setting:glowing-runners-every-minutes", "setting:glowing-runners-seconds"})
    void glowing() {
        try (Game game = hunt("variant-glowing", Map.of("glowing-runners-every-minutes", "1",
                "glowing-runners-seconds", "5"))) {
            Bot ada = game.admin("Ada");
            Bot bo = game.player("Bo");
            Game.awaitLobbyItems(bo);
            bo.run("manhunt join runner");
            bo.expectChat("You are running");
            Game.startRun(ada);
            Game.awaitRacing(bo);

            Await.until("everybody is warned the Runners will glow", Duration.ofSeconds(90),
                    () -> ada.chatText().stream().anyMatch(line -> line.contains("glow in ten seconds")));
            Await.until("the Runners glow", Duration.ofSeconds(20),
                    () -> ada.chatText().stream().anyMatch(line -> line.contains("Runners glow for")));
            Game.covered("behaviour:variant:glowing", "setting:glowing-runners-every-minutes",
                    "setting:glowing-runners-seconds");
        }
    }

    @Test
    @DisplayName("the head start grows with every Hunter beyond the number of Runners")
    @Covers({"behaviour:variant:head-start-scaling", "setting:head-start-per-hunter-seconds",
            "setting:hunter-head-start-seconds", "setting:hud-head-start-bar"})
    void headStartScaling() {
        try (Game game = hunt("variant-headstart", Map.of("hunter-head-start-seconds", "4",
                "head-start-per-hunter-seconds", "3", "hud-head-start-bar", "true"))) {
            Bot ada = game.admin("Ada");
            Bot bo = game.player("Bo");
            game.player("Cy");
            game.player("Dee");
            Game.awaitLobbyItems(bo);
            bo.run("manhunt join runner");
            bo.expectChat("You are running");
            Game.startRun(ada);
            // One Runner, three Hunters: 4 s and 3 s for each of the two Hunters beyond the Runners.
            ada.expectChat("10-second head start");
            ada.expectBossBar("held for");
            Game.covered("behaviour:variant:head-start-scaling", "setting:head-start-per-hunter-seconds",
                    "setting:hunter-head-start-seconds", "setting:hud-head-start-bar");
        }
    }

    @Test
    @DisplayName("a Runner offline longer than the grace is caught, and the hunt is the Hunters'")
    @Covers({"behaviour:manhunt:offline-grace", "setting:runner-offline-grace-seconds"})
    void offlineGrace() {
        try (Game game = hunt("variant-grace", Map.of("runner-offline-grace-seconds", "5"))) {
            Bot ada = game.admin("Ada");
            Bot bo = game.player("Bo");
            Game.awaitLobbyItems(bo);
            bo.run("manhunt join runner");
            bo.expectChat("You are running");
            Game.startRun(ada);
            Game.awaitRacing(bo);

            bo.leave();
            ada.expectChat("stayed away");
            ada.expectChat("Hunters");
            Game.covered("behaviour:manhunt:offline-grace", "setting:runner-offline-grace-seconds");
        }
    }
}
