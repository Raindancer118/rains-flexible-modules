package de.raindancer.e2e.speedrun;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A hunt as three players see it: the sides, each side's compasses, the Hunters held through the head
 * start while the Runner goes, the needle on the Runner, a compass that will not go into a chest or
 * onto the ground and comes back after a death, {@code /manhunt give all}, and the last Runner caught.
 */
@Tag("e2e")
class ManhuntScenarioTest {

    static final Predicate<Bot.Item> TRACKER = item -> item.tag("manhunt-tracker").isPresent();
    static final Predicate<Bot.Item> TEAM = item -> item.tag("manhunt-team-compass").isPresent();
    static final Predicate<Bot.Item> STRUCTURE = item -> item.tag("manhunt-structure-compass").isPresent();

    @Test
    @DisplayName("a hunt: sides, compasses by side, head start, needle, bound compasses, give all, the catch")
    @Covers({"behaviour:manhunt:sides", "behaviour:manhunt:hunter-compass", "behaviour:manhunt:runner-compass",
            "behaviour:manhunt:team-compass", "behaviour:manhunt:structure-compass",
            "behaviour:manhunt:head-start-freeze", "behaviour:compass:points-at-runner",
            "behaviour:compass:no-chest", "behaviour:compass:no-drop", "behaviour:compass:back-after-death",
            "behaviour:compass:right-click-cycles", "behaviour:manhunt:give-all", "behaviour:manhunt:caught",
            "word:manhunt:join", "word:manhunt:give", "word:manhunt:status"})
    void hunt() {
        try (Game game = Game.start("manhunt", Map.of("game-mode", "manhunt"))) {
            game.set("hunter-head-start-seconds", "6");
            game.set("runner-compass", "true");
            game.set("tracker-team-compass", "true");
            game.set("runner-structure-compass", "true");
            Bot ada = game.admin("Ada");
            Bot bo = game.player("Bo");
            Bot cy = game.player("Cy");
            Game.awaitLobbyItems(bo);
            bo.run("manhunt join runner");
            bo.expectChat("You are running");
            cy.run("manhunt status");
            cy.expectChat("Running");
            Game.covered("word:manhunt:join", "word:manhunt:status");

            Game.startRun(ada);
            Game.awaitRacing(ada);
            Game.awaitRacing(bo);

            // Each side what it gets: a tracker for every Hunter, and for the Runner too with runner-compass;
            // the team compass for everybody; the structure compass for the Runner alone.
            ada.expectItem("the Hunter's tracking compass", TRACKER);
            cy.expectItem("the Hunter's tracking compass", TRACKER);
            Game.covered("behaviour:manhunt:sides", "behaviour:manhunt:hunter-compass");
            bo.expectItem("the Runner's own tracking compass", TRACKER);
            Game.covered("behaviour:manhunt:runner-compass");
            ada.expectItem("the team compass", TEAM);
            bo.expectItem("the team compass", TEAM);
            Game.covered("behaviour:manhunt:team-compass");
            bo.expectItem("the structure compass", STRUCTURE);
            assertThat(ada.carrying(STRUCTURE)).as("a Hunter has no structure compass").isEmpty();
            Game.covered("behaviour:manhunt:structure-compass");

            // The head start: the Hunters stand still, the Runner runs.
            var adaAt = ada.position();
            ada.step(4, 0);
            Await.until("Ada is held in place in the head start", Duration.ofSeconds(5),
                    () -> Math.floor(ada.position().getX()) == Math.floor(adaAt.getX()));
            var boAt = bo.position();
            bo.step(4, 0);
            Await.never("Bo is held back", Duration.ofSeconds(2),
                    () -> Math.floor(bo.position().getX()) == Math.floor(boAt.getX()));
            ada.expectChat("head start");
            ada.expectBossBar("held for");
            ada.expectChat("The Hunters are loose!");
            Game.covered("behaviour:manhunt:head-start-freeze");

            // The needle follows the Runner: the compass is named after him.
            Await.until("Ada's compass tracks Bo", Duration.ofSeconds(20),
                    () -> ada.carrying(TRACKER).map(item -> item.name().contains("Bo")).orElse(false));
            Game.covered("behaviour:compass:points-at-runner");

            // A plain right-click into the air steps the needle along — Bukkit fires that click already
            // cancelled, which the tracker once ignored (found by this run).
            game.set("tracker-hunter-may-choose", "true");
            ada.forgetChat();
            ada.hold(ada.hotbarSlotOf(TRACKER)).useHeld();
            ada.expectChat("Following");
            Game.covered("behaviour:compass:right-click-cycles");

            // A compass never leaves its holder: not onto the ground, not into a chest.
            ada.hold(ada.hotbarSlotOf(TRACKER)).dropHeld();
            Await.ticks(20);
            assertThat(ada.carrying(TRACKER)).as("the tracker stays with Ada").isPresent();
            Game.covered("behaviour:compass:no-drop");
            int x = (int) Math.floor(ada.position().getX()) + 1;
            int y = (int) Math.floor(ada.position().getY());
            int z = (int) Math.floor(ada.position().getZ());
            game.server.console("execute in " + ada.world() + " run setblock " + x + " " + y + " " + z + " minecraft:chest");
            // An empty hand: a right-click with the compass is the compass's own, not the chest's.
            ada.hold(8).useOn(x, y, z);
            Bot.Window chest = ada.awaitWindow("container.chest");
            int compassSlot = chest.items().entrySet().stream()
                    .filter(entry -> entry.getKey() >= chest.size() && TRACKER.test(entry.getValue()))
                    .map(Map.Entry::getKey).findFirst().orElseThrow();
            ada.shiftClickSlot(compassSlot);
            Await.ticks(20);
            assertThat(ada.window().orElseThrow().top().values()).as("nothing went into the chest")
                    .noneMatch(TRACKER);
            ada.closeWindow();
            assertThat(ada.carrying(TRACKER)).isPresent();
            Game.covered("behaviour:compass:no-chest");

            // Back after a death.
            game.server.console("kill Cy");
            Await.until("Cy is dead", Duration.ofSeconds(10), cy::isDead);
            cy.respawn();
            cy.expectItem("a tracking compass again after respawning", TRACKER);
            Game.covered("behaviour:compass:back-after-death");

            // /manhunt give all: whoever lacks a compass of theirs gets it back.
            game.server.console("clear Cy");
            cy.expectNoItem("compasses", TRACKER);
            ada.run("manhunt give all");
            cy.expectItem("the tracker from give all", TRACKER);
            Game.covered("behaviour:manhunt:give-all", "word:manhunt:give");

            // The last Runner caught ends it: the Hunters win.
            game.server.console("kill Bo");
            Await.until("Bo is dead", Duration.ofSeconds(10), bo::isDead);
            bo.respawn();
            ada.expectChat("Hunters");
            Await.until("Bo watches, caught", Duration.ofSeconds(20),
                    () -> bo.gameMode() == GameMode.SPECTATOR || bo.carrying(TRACKER).isEmpty());
            Game.covered("behaviour:manhunt:caught");
            assertThat(game.server.errorsFrom("RainsSpeedrun", "RainsCore")).isEmpty();
        }
    }
}
