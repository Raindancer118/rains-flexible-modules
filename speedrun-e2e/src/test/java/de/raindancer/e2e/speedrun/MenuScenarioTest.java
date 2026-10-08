package de.raindancer.e2e.speedrun;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import de.raindancer.e2e.MenuCrawler;
import de.raindancer.e2e.MenuCrawler.Route;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every page of RainsSpeedrun and Manhunt, and every button on it, clicked by a bot in every state the
 * page looks different in — a ready lobby, a run under way, a finished one with a history, a hunt — and
 * each click checked to do something a player would notice.
 */
@Tag("e2e")
class MenuScenarioTest {

    private static final String MODULE = "de.raindancer.modules.speedrun";

    private static MenuCrawler crawler(Game game, Bot bot) {
        // Commits other scenarios play, each with its own assertions: starting a run, and the world reset
        // a confirmation page guards (that page is Core's and is only opened here, never confirmed).
        MenuCrawler crawler = new MenuCrawler(game.server, bot, holder -> holder.startsWith(MODULE))
                .skip("Start the countdown")
                .informational("What is this?", "Core's help icon: its lore is the help")
                .informational("✔ ", "a check that is fine has nothing to fix")
                .informational("Not ready yet", "the checklist's summary, read from its lore")
                .informational("Ready", "the checklist's summary, read from its lore")
                .informational("No run", "an empty list says so; there is nothing to open yet")
                .informational("In use.", "the option already chosen: choosing it again changes nothing")
                .informational("Already on.", "a switch already on: its lore says so")
                .readOnly("SpeedrunRunSummaryMenu", "a run's timeline is read, not acted on");
        // A locked button is greyed with its reason as the last lore line — Core's answer, read by hovering.
        for (String reason : List.of("A run is already under way", "No run is being played",
                "Not offered on this server", "Only during a hunt", "Only from a ready or finished lobby",
                "Only staff", "Staff change the settings", "Staff choose the seed", "Staff resume runs",
                "Staff set the clock", "Staff set the lobby up", "Fix the red lines first",
                "Sides do not move during a hunt")) {
            crawler.informational(reason, "locked: " + reason);
        }
        return crawler;
    }

    private static void assertAllAnswered(MenuCrawler crawler) {
        assertThat(crawler.clicked()).isNotEmpty();
        assertThat(crawler.silentButtons()).as("buttons whose click changed nothing a player could see").isEmpty();
        assertThat(crawler.unreachable()).as("buttons the crawl could not get back to").isEmpty();
    }

    @Test
    @DisplayName("a ready lobby: every page and button of the race")
    @Covers({"menu:SpeedrunLobbyMenu", "menu:SpeedrunGoalMenu", "menu:SpeedrunAdvancementChooser",
            "menu:SpeedrunAdvancementChooser.WithinCategory", "menu:SpeedrunHazardMenu", "menu:SpeedrunSeedMenu",
            "menu:SpeedrunSetupMenu", "menu:SpeedrunStatsMenu", "menu:SpeedrunLeaderboardMenu",
            "menu:SpeedrunHistoryMenu", "menu:SpeedrunRosterMenu"})
    void raceReady() {
        try (Game game = Game.start("menus-race-ready")) {
            Bot ada = game.admin("Ada");
            Bot bo = game.player("Bo");
            Game.awaitLobbyItems(bo);
            // The hub's game switch turns the lobby into a hunt, whose hub has no goal; the setup assistant's
            // answers are written at once: the goal and game go back after each click.
            Runnable putBack = () -> {
                game.server.console("settings set game-mode speedrun");
                game.server.console("settings set advancement-key minecraft:end/kill_dragon");
            };
            // The assistant last, on its own: eight pages of answers change the lobby under everything else.
            MenuCrawler ready = crawler(game, ada).between(putBack).skip("Setup assistant");
            for (String command : List.of("speedrun menu", "speedrun start", "speedrun seed",
                    "speedrun stats", "speedrun top", "speedrun history")) {
                ready.crawl(Route.command(command));
            }
            assertAllAnswered(ready);
            ada.runAndOpen("speedrun menu", "Speedrun");
            ada.answer(() -> ada.click("Setup assistant"), answer -> answer.opened("Setup"));
            // Opened from the hub, the assistant has a way back to it; from the command it does not.
            ada.answer(() -> ada.clickSlot(45), answer -> answer.opened("Speedrun"));
            ada.closeWindow();
            MenuCrawler assistant = crawler(game, ada).between(putBack);
            assistant.crawl(Route.command("speedrun setup"));
            assertAllAnswered(assistant);
            java.util.Set<String> pages = new java.util.LinkedHashSet<>(ready.pages());
            pages.addAll(assistant.pages());
            covered(pages, "SpeedrunLobbyMenu", "SpeedrunGoalMenu", "SpeedrunAdvancementChooser",
                    "WithinCategory", "SpeedrunHazardMenu", "SpeedrunSeedMenu", "SpeedrunSetupMenu",
                    "SpeedrunStatsMenu", "SpeedrunLeaderboardMenu", "SpeedrunHistoryMenu", "SpeedrunRosterMenu");
        }
    }

    @Test
    @DisplayName("a run under way, and after it: the hub, the history, a run's summary, stats and the board with runs on it")
    @Covers({"menu:SpeedrunRunSummaryMenu"})
    void raceRunningAndAfter() {
        try (Game game = Game.start("menus-race-run", Map.of("advancement-key", "minecraft:story/mine_stone",
                "restart-when-run-ends", "false"))) {
            Bot ada = game.admin("Ada");
            Bot bo = game.player("Bo");
            Game.awaitLobbyItems(bo);
            Game.startRun(ada);
            Game.awaitRacing(bo);
            MenuCrawler running = crawler(game, ada).skip("Reset", "regenerate", "Setup assistant");
            running.crawl(Route.command("speedrun menu"));
            assertAllAnswered(running);

            game.server.console("advancement grant Bo only minecraft:story/mine_stone");
            bo.expectChat("Run finished!");
            MenuCrawler after = crawler(game, ada).skip("Reset", "regenerate", "Setup assistant");
            for (String command : List.of("speedrun menu", "speedrun history", "speedrun stats Bo", "speedrun top")) {
                after.crawl(Route.command(command));
            }
            assertAllAnswered(after);
            covered(after.pages(), "SpeedrunRunSummaryMenu");
        }
    }

    /** Records each page as played, after checking the crawl reached it. */
    private static void covered(java.util.Set<String> pages, String... names) {
        String all = String.join(" ", pages);
        for (String name : names) {
            assertThat(all).as("the crawl reached " + name).contains(name);
            Game.covered("menu:" + (name.equals("WithinCategory") ? "SpeedrunAdvancementChooser.WithinCategory" : name));
        }
    }

    @Test
    @DisplayName("Manhunt before a hunt: the hub, the sides, the pre-flight check")
    @Covers({"menu:ManhuntHubMenu", "menu:SidesEditorMenu", "menu:SpeedrunPreflightMenu"})
    void huntLobby() {
        try (Game game = Game.start("menus-hunt-lobby", Map.of("game-mode", "manhunt"))) {
            Bot ada = game.admin("Ada");
            Bot bo = game.player("Bo");
            Game.awaitLobbyItems(bo);
            MenuCrawler lobby = crawler(game, ada);
            lobby.crawl(Route.command("manhunt"));
            lobby.crawl(Route.command("manhunt sides"));
            lobby.crawl(Route.command("manhunt preflight"));
            assertAllAnswered(lobby);
            // A player's hub is not staff's: their side's buttons — run, hunt, leave.
            MenuCrawler player = crawler(game, bo);
            player.crawl(Route.command("manhunt"));
            assertAllAnswered(player);
            covered(lobby.pages(), "ManhuntHubMenu", "SidesEditorMenu", "SpeedrunPreflightMenu");
        }
    }

    @Test
    @DisplayName("during a hunt and after: the hub, the tracker list, the structure list, the players' board")
    @Covers({"menu:ManhuntTrackerMenu", "menu:StructureChoiceMenu", "menu:SpeedrunStandingsMenu"})
    void hunting() {
        try (Game game = Game.start("menus-hunting", Map.of("game-mode", "manhunt", "restart-when-run-ends", "false"))) {
            game.set("runner-structure-compass", "true");
            Bot ada = game.admin("Ada");
            Bot bo = game.player("Bo");
            Game.awaitLobbyItems(bo);
            ada.runAndExpect("manhunt join hunter", "You are hunting");
            bo.runAndExpect("manhunt join runner", "You are running");
            Game.startRun(ada);
            Game.awaitRacing(bo);
            ada.expectItem("the tracker", ManhuntScenarioTest.TRACKER);
            bo.expectItem("the structure compass", ManhuntScenarioTest.STRUCTURE);

            MenuCrawler hunt = crawler(game, ada).skip("Reset", "regenerate", "Setup assistant");
            hunt.crawl(Route.command("manhunt"));
            hunt.crawl(Route.by("sneak + right-click the tracker", hunter -> {
                hunter.hold(hunter.hotbarSlotOf(ManhuntScenarioTest.TRACKER));
                hunter.sneak(true);
                hunter.useHeld();
                hunter.sneak(false);
            }));
            assertAllAnswered(hunt);

            // One choice per hunt: a fresh compass between clicks hands it back.
            MenuCrawler structures = crawler(game, bo).between(() -> game.server.console("manhunt give Bo structure"))
                    .oneShot("StructureChoiceMenu", "one choice per hunt: once a structure is found the compass is spent");
            structures.crawl(Route.by("right-click the structure compass", runner -> {
                runner.expectItem("a blank structure compass", ManhuntScenarioTest.STRUCTURE);
                runner.hold(runner.hotbarSlotOf(ManhuntScenarioTest.STRUCTURE));
                runner.useHeld();
            }));
            assertAllAnswered(structures);

            game.server.console("kill Bo");
            Await.until("Bo is dead", Duration.ofSeconds(10), bo::isDead);
            bo.respawn();
            ada.expectChat("Hunters won");
            MenuCrawler board = crawler(game, ada);
            board.crawl(Route.command("manhunt top"));
            assertAllAnswered(board);
            java.util.Set<String> pages = new java.util.LinkedHashSet<>(hunt.pages());
            pages.addAll(structures.pages());
            pages.addAll(board.pages());
            covered(pages, "ManhuntTrackerMenu", "StructureChoiceMenu", "SpeedrunStandingsMenu");
        }
    }
}
