package de.raindancer.e2e.speedrun;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Every command and every word of {@code /speedrun}, {@code /manhunt} and {@code /whitelist}: done by
 * staff, each with the answer it is meant to give; and tried by an ordinary player, refused for every
 * one that needs a node they do not have — with the refusal, and without the effect.
 */
@Tag("e2e")
class CommandWordsScenarioTest {

    /** Any of the ways a player is told no: the module's own refusals, or the server's for a command they cannot see. */
    private static final List<String> REFUSALS = List.of("is for staff", "may not use", "not yours to do",
            "Only staff can start", "Unknown or incomplete command", "command.unknown.command", "do not have permission",
            "changing who is on the whitelist is for admins");

    private static void refused(Bot player, String command, String id) {
        player.answer(() -> player.run(command), answer -> REFUSALS.stream().anyMatch(answer::says));
        Game.covered(id);
    }

    @Test
    @DisplayName("/speedrun: every word answers staff as it should, and refuses a player every word that is staff's")
    @Covers({"word:speedrun:menu", "word:speedrun:join", "word:speedrun:check", "word:speedrun:stats",
            "word:speedrun:top", "word:speedrun:history", "word:speedrun:hud", "word:speedrun:seed",
            "word:speedrun:setup", "word:speedrun:help", "word:speedrun:reset", "command:speedrun",
            "refused:speedrun:start", "refused:speedrun:resume", "refused:speedrun:time", "refused:speedrun:reset",
            "refused:speedrun:seed", "refused:speedrun:setup", "command:speedrunspectate", "command:starthere",
            "refused:starthere", "refused:speedrunreset", "refused:speedrunresume", "refused:speedruntime",
            "command:lemmemove", "command:freezeagain", "chat:code:[Yes, regenerate it]", "chat:code:[Try]",
            "chat:code:[Open the menu]", "chat:speedrun.command.did-you-mean"})
    void speedrun() {
        try (Game game = Game.start("words-speedrun")) {
            Bot ada = game.admin("Ada");
            Bot bo = game.player("Bo");
            Game.awaitLobbyItems(bo);

            ada.runAndOpen("speedrun menu", "Speedrun");
            ada.closeWindow();
            Game.covered("word:speedrun:menu");
            game.server.console("execute in minecraft:overworld run tp Ada 0 100 0");
            ada.expectWorld("overworld");
            ada.run("speedrun join");
            ada.expectWorld("speedrun");
            Game.covered("word:speedrun:join");
            // /speedrun alone, from outside the lobby: there, and a button to the menu.
            game.server.console("execute in minecraft:overworld run tp Ada 0 100 0");
            ada.expectWorld("overworld");
            ada.runAndExpect("speedrun", "Welcome to the speedrun lobby");
            ada.clickChat("/speedrun menu");
            ada.awaitWindow("Speedrun");
            ada.closeWindow();
            Game.covered("command:speedrun", "chat:code:[Open the menu]");
            ada.runAndExpect("speedrun check", "Before the start");
            Game.covered("word:speedrun:check");
            ada.answer(() -> ada.run("speedrun stats"), answer -> answer.window().isPresent());
            ada.closeWindow();
            Game.covered("word:speedrun:stats");
            ada.runAndOpen("speedrun top", "Leaderboard");
            ada.closeWindow();
            Game.covered("word:speedrun:top");
            ada.answer(() -> ada.run("speedrun history"), answer -> answer.window().isPresent());
            ada.closeWindow();
            Game.covered("word:speedrun:history");
            ada.runAndExpect("speedrun hud bossbar", "Boss bar");
            ada.runAndExpect("speedrun hud sidebar", "Sidebar");
            Game.covered("word:speedrun:hud");
            ada.runAndExpect("speedrun seed 4242", "made from seed 4242");
            ada.answer(() -> ada.run("speedrun seed"), answer -> answer.window().isPresent());
            ada.closeWindow();
            Game.covered("word:speedrun:seed");
            ada.answer(() -> ada.run("speedrun setup"), answer -> answer.window().isPresent());
            ada.closeWindow();
            Game.covered("word:speedrun:setup");
            ada.runAndExpect("speedrun help", "everything the lobby does");
            Game.covered("word:speedrun:help");
            // The help's [Try] puts the command in the chat box — a suggestion the client would type.
            ada.clickChat("/speedrun menu ");
            Game.covered("chat:code:[Try]");
            ada.runAndExpect("speedrun strat", "did you mean");
            ada.clickChat("/speedrun start");
            ada.awaitWindow("Before the");
            ada.closeWindow();
            Game.covered("chat:speedrun.command.did-you-mean");

            // An ordinary player: every staff word refused, and nothing changed by it.
            game.set("start-block-staff-only", "true");
            refused(bo, "speedrun start", "refused:speedrun:start");
            refused(bo, "speedrun resume 1:00", "refused:speedrun:resume");
            refused(bo, "speedrun time 1:00", "refused:speedrun:time");
            refused(bo, "speedrun reset", "refused:speedrun:reset");
            refused(bo, "speedrun seed 99", "refused:speedrun:seed");
            refused(bo, "speedrun setup", "refused:speedrun:setup");
            refused(bo, "starthere", "refused:starthere");
            refused(bo, "speedrunreset", "refused:speedrunreset");
            refused(bo, "speedrunresume 1:00", "refused:speedrunresume");
            refused(bo, "speedruntime 1:00", "refused:speedruntime");
            Await.ticks(10);
            org.assertj.core.api.Assertions.assertThat(game.get("seed")).as("the refused seed changed nothing").doesNotContain("99");
            game.set("start-block-staff-only", "false");

            bo.runAndExpect("speedrunspectate", "not racing");
            bo.runAndExpect("speedrunspectate", "racing again");
            Game.covered("command:speedrunspectate");
            ada.runAndExpect("starthere", "Start point set");
            Game.covered("command:starthere");

            // Released during a countdown, and frozen again.
            Game.startRun(ada);
            ada.expectBossBar("");
            bo.runAndExpect("lemmemove", "");
            Game.covered("command:lemmemove");
            bo.answer(() -> bo.run("freezeagain"), answer -> !answer.chat().isEmpty());
            Game.covered("command:freezeagain");
            Game.awaitRacing(bo);

            // /speedrun reset asks first, with a button that does it.
            ada.runAndExpect("speedrun reset", "Sure?");
            ada.clickButtonOn("Sure?", 0);
            Await.until("the lobby is remade, items back", Duration.ofSeconds(120),
                    () -> bo.carrying(item -> item.tag("speedrun-lobby-item").isPresent()).isPresent());
            Game.covered("word:speedrun:reset", "chat:code:[Yes, regenerate it]");
        }
    }

    @Test
    @DisplayName("/manhunt and /whitelist: every word answers staff, and every staff word refuses a player")
    @Covers({"word:manhunt:hub", "word:manhunt:leave", "word:manhunt:stats", "word:manhunt:top",
            "word:manhunt:history", "word:manhunt:summary", "word:manhunt:trail", "word:manhunt:here",
            "word:manhunt:hud", "word:manhunt:announcements", "word:manhunt:sides", "word:manhunt:assign",
            "word:manhunt:unassign", "word:manhunt:balance", "word:manhunt:random", "word:manhunt:preflight",
            "word:manhunt:start", "word:manhunt:resume", "word:manhunt:goal", "word:manhunt:door",
            "word:manhunt:reset", "word:manhunt:setup", "command:manhunt", "command:whitelist",
            "word:whitelist:open", "word:whitelist:close", "word:whitelist:clear", "word:whitelist:vip",
            "refused:manhunt:sides", "refused:manhunt:assign", "refused:manhunt:unassign", "refused:manhunt:balance",
            "refused:manhunt:random", "refused:manhunt:preflight", "refused:manhunt:start", "refused:manhunt:resume",
            "refused:manhunt:give", "refused:manhunt:goal", "refused:manhunt:door", "refused:manhunt:reset",
            "refused:manhunt:setup", "refused:whitelist:open", "refused:whitelist:close", "refused:whitelist:clear",
            "refused:whitelist:vip", "chat:code:position-share", "chat:manhunt.start.no-hunter"})
    void manhunt() {
        try (Game game = Game.start("words-manhunt", Map.of("game-mode", "manhunt"))) {
            Bot ada = game.admin("Ada");
            Bot bo = game.player("Bo");
            Game.awaitLobbyItems(bo);

            ada.runAndOpen("manhunt", "Manhunt");
            ada.closeWindow();
            ada.runAndOpen("manhunt hub", "Manhunt");
            ada.closeWindow();
            Game.covered("command:manhunt", "word:manhunt:hub");
            bo.runAndExpect("manhunt join runner", "You are running");
            bo.runAndExpect("manhunt leave", "with everybody else again");
            Game.covered("word:manhunt:leave");
            ada.answer(() -> ada.run("manhunt stats"), answer -> answer.window().isPresent());
            ada.closeWindow();
            Game.covered("word:manhunt:stats");
            ada.answer(() -> ada.run("manhunt top"), answer -> answer.window().isPresent());
            ada.closeWindow();
            Game.covered("word:manhunt:top");
            ada.answer(() -> ada.run("manhunt history"), answer -> answer.window().isPresent());
            ada.closeWindow();
            Game.covered("word:manhunt:history");
            ada.runAndExpect("manhunt summary 7", "no hunt #7");
            Game.covered("word:manhunt:summary");
            ada.runAndExpect("manhunt trail", "no longer shows the particle trail");
            ada.runAndExpect("manhunt trail", "shows the particle trail again");
            Game.covered("word:manhunt:trail");
            ada.runAndExpect("manhunt hud", "Your splits are shown");
            Game.covered("word:manhunt:hud");
            ada.runAndExpect("manhunt announcements", "off for you");
            ada.runAndExpect("manhunt announcements", "Split titles and sounds are on");
            Game.covered("word:manhunt:announcements");
            ada.runAndOpen("manhunt sides", "Sides");
            ada.closeWindow();
            Game.covered("word:manhunt:sides");
            ada.runAndExpect("manhunt assign", "/manhunt assign, a player");
            ada.runAndExpect("manhunt assign Bo runner", "Bo is now on the");
            Game.covered("word:manhunt:assign");
            ada.runAndExpect("manhunt unassign Bo", "is on no side now");
            Game.covered("word:manhunt:unassign");
            ada.runAndExpect("manhunt balance", "Balanced by rating");
            Game.covered("word:manhunt:balance");
            ada.runAndExpect("manhunt random 1", "Drawn by lot");
            Game.covered("word:manhunt:random");
            ada.runAndOpen("manhunt preflight", "Before the");
            ada.closeWindow();
            Game.covered("word:manhunt:preflight");
            ada.answer(() -> ada.run("manhunt goal"), answer -> answer.window().isPresent());
            ada.closeWindow();
            Game.covered("word:manhunt:goal");
            ada.runAndExpect("manhunt door keep-open", "leave the server open");
            ada.runAndExpect("manhunt door close-on-start", "closes to newcomers");
            Game.covered("word:manhunt:door");
            game.set("close-whitelist-on-start", "false");
            ada.answer(() -> ada.run("manhunt setup"), answer -> answer.window().isPresent());
            ada.closeWindow();
            ada.runAndExpect("manhunt setup casual", "hunt is set");
            Game.covered("word:manhunt:setup");
            ada.answer(() -> ada.run("manhunt resume"), answer -> answer.window().isPresent());
            ada.closeWindow();
            Game.covered("word:manhunt:resume");

            // Refusals: every staff word, by somebody who is not.
            // The sides: a player is shown the hub, never the editor that moves people between them.
            bo.answer(() -> bo.run("manhunt sides"), answer -> answer.window().isPresent()
                    && !answer.opened("Sides"));
            bo.closeWindow();
            Game.covered("refused:manhunt:sides");
            for (String word : List.of("assign Bo runner", "unassign Bo", "balance", "random 1", "preflight",
                    "start", "resume 1:00", "give all", "goal remove", "door keep-open", "reset", "setup classic")) {
                refused(bo, "manhunt " + word, "refused:manhunt:" + word.split(" ")[0]);
            }
            refused(bo, "whitelist open", "refused:whitelist:open");
            refused(bo, "whitelist close", "refused:whitelist:close");
            refused(bo, "whitelist clear", "refused:whitelist:clear");
            refused(bo, "whitelist vip list", "refused:whitelist:vip");

            // The door and the list, by staff.
            ada.runAndExpect("whitelist close", "The server is closed");
            Game.covered("word:whitelist:close");
            ada.runAndExpect("whitelist open", "open again");
            Game.covered("word:whitelist:open", "command:whitelist");
            ada.runAndExpect("whitelist vip add Bo", "Bo is a VIP now");
            ada.runAndExpect("whitelist vip list", "VIPs:");
            Game.covered("word:whitelist:vip");
            ada.runAndExpect("whitelist clear", "Whitelist cleared");
            Game.covered("word:whitelist:clear");

            // Everybody a Runner: the start is refused, with what is missing a click away.
            ada.runAndExpect("manhunt join runner", "You are running");
            bo.runAndExpect("manhunt join runner", "You are running");
            ada.runAndExpect("manhunt start", "Everybody here is a Runner");
            Game.covered("chat:manhunt.start.no-hunter");

            // A hunt: start, share where you are, reset.
            ada.runAndExpect("manhunt join hunter", "You are hunting");
            ada.runAndExpect("manhunt start", "countdown");
            Game.awaitRacing(bo);
            Game.covered("word:manhunt:start");
            bo.runAndExpect("manhunt here", "");
            ada.expectChat("Bo");
            Game.covered("word:manhunt:here", "chat:code:position-share");
            ada.answer(() -> ada.run("manhunt reset"), answer -> !answer.chat().isEmpty() || answer.window().isPresent());
            Game.covered("word:manhunt:reset");
        }
    }
}
