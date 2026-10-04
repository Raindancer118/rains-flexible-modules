package de.raindancer.e2e.speedrun;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every chat button: the line that carries it brought about the way a player would, the button clicked
 * as the client sends it, and what it did checked — a page, an answer, a change.
 */
@Tag("e2e")
class ChatButtonsScenarioTest {

    /** Brings the line about, clicks button {@code index} on it, and waits for {@code effect}. */
    private static void press(Bot bot, String trigger, String line, int index, Predicate<Bot.Answer> effect, String id) {
        bot.forgetChat();
        bot.run(trigger);
        bot.clicksOn(line);
        bot.answer(() -> bot.clickButtonOn(line, index), effect);
        bot.closeWindow();
        Game.covered(id);
    }

    private static Predicate<Bot.Answer> says(String text) {
        return answer -> answer.says(text);
    }

    private static Predicate<Bot.Answer> opens(String title) {
        return answer -> answer.opened(title);
    }

    private static final Predicate<Bot.Answer> A_PAGE = answer -> answer.window().isPresent();

    @Test
    @DisplayName("Manhunt's buttons: every line with a click in it, clicked")
    @Covers({"chat:manhunt.did-you-mean", "chat:manhunt.unknown-word", "chat:manhunt.no-such-player",
            "chat:manhunt.goal.usage", "chat:manhunt.goal.unknown", "chat:manhunt.start.what-is-missing",
            "chat:manhunt.start.no-runner", "chat:manhunt.join.which-side", "chat:manhunt.join.runners-locked",
            "chat:manhunt.assign.usage", "chat:manhunt.status.waiting", "chat:manhunt.give.usage",
            "chat:manhunt.trail.off", "chat:manhunt.whitelist.vip.usage", "chat:manhunt.announcements.off",
            "chat:manhunt.summary.none", "chat:manhunt.random.usage", "chat:manhunt.unassign.usage",
            "chat:manhunt.door.kept-open", "chat:manhunt.door.usage", "chat:manhunt.setup.applied",
            "chat:manhunt.setup.usage", "chat:manhunt.stats.unknown", "chat:manhunt.top.usage",
            "chat:manhunt.sides-frozen", "chat:manhunt.side.not-in-hunt", "chat:manhunt.summary.open-button"})
    void manhunt() {
        try (Game game = Game.start("chat-manhunt", Map.of("game-mode", "manhunt", "restart-when-run-ends", "false"))) {
            Bot ada = game.admin("Ada");
            Bot bo = game.player("Bo");
            Game.awaitLobbyItems(bo);

            press(ada, "manhunt statuss", "did you mean", 0, says("Running:"), "chat:manhunt.did-you-mean");
            press(ada, "manhunt xyzzy", "No such thing as", 0, opens("Manhunt"), "chat:manhunt.unknown-word");
            press(ada, "manhunt give Nobody tracker", "No online player named", 0, opens("Sides"), "chat:manhunt.no-such-player");
            press(ada, "manhunt goal xyz", "/manhunt goal remove, or", 0, A_PAGE, "chat:manhunt.goal.usage");
            press(ada, "manhunt goal set nope:nothing", "is no advancement", 0, A_PAGE, "chat:manhunt.goal.unknown");
            press(ada, "manhunt start", "What is missing", 0, opens("Before the"), "chat:manhunt.start.what-is-missing");
            press(ada, "manhunt start", "Nobody is running", 0, says("You are running"), "chat:manhunt.start.no-runner");
            press(bo, "manhunt join", "Which side?", 0, says("You are running"), "chat:manhunt.join.which-side");
            game.set("runner-self-join", "false");
            press(bo, "manhunt join runner", "An admin picks the Runners", 0, says("You are hunting"), "chat:manhunt.join.runners-locked");
            game.set("runner-self-join", "true");
            press(ada, "manhunt assign", "/manhunt assign, a player", 1, opens("Sides"), "chat:manhunt.assign.usage");
            press(ada, "manhunt status", "Running:", 0, opens("Manhunt"), "chat:manhunt.status.waiting");
            press(ada, "manhunt give", "/manhunt give, a player or all", 0, says("hunt"), "chat:manhunt.give.usage");
            press(ada, "manhunt trail", "no longer shows the particle trail", 0, says("shows the particle trail again"), "chat:manhunt.trail.off");
            press(ada, "whitelist vip", "/whitelist vip add or remove", 0, says("VIP"), "chat:manhunt.whitelist.vip.usage");
            press(ada, "manhunt announcements", "off for you", 0, says("are on"), "chat:manhunt.announcements.off");
            press(ada, "manhunt summary 9", "no hunt #9", 0, A_PAGE, "chat:manhunt.summary.none");
            press(ada, "manhunt random x", "/manhunt random and how many", 0, says("Drawn by lot"), "chat:manhunt.random.usage");
            press(ada, "manhunt unassign", "/manhunt unassign and a player", 1, opens("Sides"), "chat:manhunt.unassign.usage");
            press(ada, "manhunt door keep-open", "leave the server open", 0, says("closes to newcomers"), "chat:manhunt.door.kept-open");
            press(ada, "manhunt door", "What should a hunt do", 0, says("leave the server open"), "chat:manhunt.door.usage");
            press(ada, "manhunt setup classic", "hunt is set", 0, opens("Manhunt"), "chat:manhunt.setup.applied");
            press(ada, "manhunt setup nope", "Pick a hunt", 0, says("hunt is set"), "chat:manhunt.setup.usage");
            press(ada, "manhunt stats Nobody", "No hunts on record for", 0, A_PAGE, "chat:manhunt.stats.unknown");
            press(ada, "manhunt top nope", "Sort the leaderboard by", 0, answer -> !answer.chat().isEmpty() || answer.window().isPresent(),
                    "chat:manhunt.top.usage");

            // During a hunt: sides frozen, a stranger not in it, and the summary's button at the end.
            ada.runAndExpect("manhunt join hunter", "You are hunting");
            bo.runAndExpect("manhunt join runner", "You are running");
            Game.startRun(ada);
            Game.awaitRacing(bo);
            press(bo, "manhunt join hunter", "A hunt is under way", 0, says("The hunt is on"), "chat:manhunt.sides-frozen");
            game.set("side-switching-mid-hunt", "true");
            // An admin who came late: the button puts /manhunt assign in the chat box, sent here unfinished.
            Bot max = game.admin("Max");
            press(max, "manhunt join runner", "is not in this hunt", 0, says("/manhunt assign, a player"), "chat:manhunt.side.not-in-hunt");

            game.server.console("kill Bo");
            Await.until("Bo is dead", Duration.ofSeconds(10), bo::isDead);
            bo.respawn();
            ada.answer(() -> ada.clickButtonOn("Every moment of it", 0), A_PAGE);
            ada.closeWindow();
            Game.covered("chat:manhunt.summary.open-button");
        }
    }

    @Test
    @DisplayName("the lobby's buttons: the setup offer, a refused start's fixes, a finished run's result")
    @Covers({"chat:code:[Set it up]", "chat:code:[Check what a start needs]", "chat:code:[Pre-flight check]",
            "chat:fix:Play a plain race", "chat:fix:Race for the dragon", "chat:fix:Create the worlds",
            "chat:fix:Bring everybody here", "chat:fix:Reset the world", "chat:code:[Summary]",
            "chat:code:[Copy seed]", "chat:code:[Same seed again]"})
    void lobby() {
        try (Game game = Game.start("chat-lobby", Map.of("restart-when-run-ends", "false"))) {
            // An admin who joins a lobby nobody set up is offered the assistant.
            Bot ada = game.admin("Ada");
            ada.rejoin();
            ada.clicksOn("has not been set up");
            ada.answer(() -> ada.clickButtonOn("has not been set up", 0), A_PAGE);
            ada.closeWindow();
            Game.covered("chat:code:[Set it up]");
            ada.rejoin();
            ada.answer(() -> ada.clickButtonOn("has not been set up", 1), says("Before the start"));
            Game.covered("chat:code:[Check what a start needs]");
            Game.awaitLobbyItems(ada);

            // A refused start from the start block, with each fix a button under it.
            Predicate<Bot.Item> startBlock = item -> item.tag("speedrun-lobby-item").filter("start"::equals).isPresent();
            Runnable pressStart = () -> ada.use(startBlock);

            game.set("game-mode", "nope");
            ada.forgetChat();
            pressStart.run();
            ada.answer(() -> ada.clickButtonOn("set to play", 0), says("plain race"));
            Game.covered("chat:fix:Play a plain race");
            ada.answer(() -> ada.clickButtonOn("set to play", 1), says("Before the start"));
            Game.covered("chat:code:[Pre-flight check]");

            // No goal: chosen on the goal page, as /settings cannot type an empty value.
            ada.runAndOpen("speedrun menu", "Speedrun");
            ada.answer(() -> ada.click("Goal:"), opens("What ends"));
            ada.answer(() -> ada.click("No goal"), says("goal cleared"));
            ada.closeWindow();
            ada.forgetChat();
            pressStart.run();
            ada.answer(() -> ada.clickButtonOn("Nothing is set to end the run", 0), says("killing the dragon"));
            Game.covered("chat:fix:Race for the dragon");

            ada.runAndExpect("speedrun spectate", "not racing");
            ada.forgetChat();
            pressStart.run();
            ada.answer(() -> ada.clickButtonOn("Nobody is here to race", 0), says("sent to the lobby"));
            Game.covered("chat:fix:Bring everybody here");
            ada.runAndExpect("speedrun spectate", "racing again");

            // A world that is not there: a resume says so, with the fix that makes it.
            game.set("world-name", "nowhere");
            ada.forgetChat();
            ada.run("speedrun resume 0:00");
            ada.answer(() -> ada.clickButtonOn("is not loaded", 0), says("worlds are there"));
            Game.covered("chat:fix:Create the worlds");
            game.set("world-name", "speedrun");

            // A run, finished: the result line's buttons; then the start block on a finished lobby.
            game.set("advancement-key", "minecraft:story/mine_stone");
            Bot bo = game.player("Bo");
            Game.awaitLobbyItems(bo);
            Game.startRun(ada);
            Game.awaitRacing(bo);
            game.server.console("advancement grant Bo only minecraft:story/mine_stone");
            assertThat(bo.clicksOn("Seed:")).anyMatch(click -> click.equals(Bot.COPY + "1"));
            Game.covered("chat:code:[Copy seed]");
            bo.answer(() -> bo.clickButtonOn("Seed:", 0), A_PAGE);
            bo.closeWindow();
            Game.covered("chat:code:[Summary]");
            ada.answer(() -> ada.clickButtonOn("Seed:", 2), says("remakes this exact map"));
            Game.covered("chat:code:[Same seed again]");

            // A start block pressed on a finished lobby — one somebody kept — offers the reset.
            game.server.console("give Ada minecraft:lime_concrete[custom_data={PublicBukkitValues:{\"rainsspeedrun:speedrun-lobby-item\":\"start\"}}]");
            ada.expectItem("a start block", startBlock);
            ada.forgetChat();
            pressStart.run();
            ada.answer(() -> ada.clickButtonOn("already under way", 0), says("Sure?"));
            Game.covered("chat:fix:Reset the world");
        }
    }
}
