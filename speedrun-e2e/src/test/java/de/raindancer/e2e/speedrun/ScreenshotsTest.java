package de.raindancer.e2e.speedrun;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import de.raindancer.e2e.RealClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A picture of every page of RainsSpeedrun and Manhunt, taken with the real Minecraft client — what a
 * player actually sees, item textures, tooltips' frames and all — into {@code docs/screenshots}.
 * Run by {@code scripts/e2e.sh --screenshots}, and in CI on every tag.
 */
@Tag("screenshots")
class ScreenshotsTest {

    private static final String SHOT = "Shot";

    private Game game;
    private RealClient client;
    private final List<String> taken = new ArrayList<>();

    private static Path output() {
        return Path.of(System.getProperty("e2e.screenshots", "../docs/screenshots")).toAbsolutePath().normalize();
    }

    /** The newest page the probe saw open for the real client since {@code mark} events. */
    private Optional<JsonObject> openedSince(int mark, String holderEnd) {
        String[] lines = game.server.eventsText().split("\n");
        for (int at = lines.length - 1; at >= Math.max(0, mark); at--) {
            if (lines[at].isBlank()) {
                continue;
            }
            JsonObject event = JsonParser.parseString(lines[at]).getAsJsonObject();
            if (event.get("event").getAsString().equals("open") && event.get("player").getAsString().equals(SHOT)
                    && event.get("holder").getAsString().endsWith(holderEnd)) {
                return Optional.of(event);
            }
        }
        return Optional.empty();
    }

    private int mark() {
        return game.server.eventsText().split("\n").length;
    }

    /** Does {@code open}, waits for {@code page} to open on the real client, and photographs it. */
    private JsonObject shoot(String page, Runnable open) {
        int mark = mark();
        open.run();
        JsonObject opened = Await.value("the real client sees " + page, Duration.ofSeconds(20),
                () -> openedSince(mark, page).orElse(null));
        // The client has the window a tick after the server; a moment more and it is drawn.
        Await.ticks(40);
        String file = page.replace('$', '.') + ".png";
        client.screenshot(output().resolve(file));
        taken.add(file);
        return opened;
    }

    private void command(String line) {
        game.server.runAs(SHOT, line);
    }

    /** The slot of the open page's item whose name contains {@code text}. */
    private static int slotNamed(JsonObject page, String text) {
        for (var element : page.getAsJsonArray("items")) {
            JsonObject item = element.getAsJsonObject();
            if (item.get("name").getAsString().contains(text)) {
                return item.get("slot").getAsInt();
            }
        }
        throw new AssertionError("no \"" + text + "\" on " + page.get("holder").getAsString());
    }

    private void click(int slot) {
        game.server.console("e2e click " + SHOT + " " + slot);
    }

    @Test
    @DisplayName("every page, photographed with the real client")
    void everyPage() throws Exception {
        try (Game started = Game.start("screenshots", Map.of("advancement-key", "minecraft:story/mine_stone",
                "restart-when-run-ends", "false", "setup-done", "true"))) {
            game = started;
            client = RealClient.join(game.server, SHOT);
            game.server.console("op " + SHOT);
            Bot bo = game.player("Bo");
            Game.awaitLobbyItems(bo);
            Files.createDirectories(output());

            // The race, in a ready lobby.
            JsonObject hub = shoot("SpeedrunLobbyMenu", () -> command("speedrun menu"));
            JsonObject goals = shoot("SpeedrunGoalMenu", () -> click(slotNamed(hub, "Goal")));
            JsonObject chooser = shoot("SpeedrunAdvancementChooser", () -> click(slotNamed(goals, "advancement")));
            shoot("WithinCategory", () -> click(chooser.getAsJsonArray("buttons").get(0).getAsInt()));
            JsonObject hub2 = shoot("SpeedrunLobbyMenu", () -> command("speedrun menu"));
            shoot("SpeedrunHazardMenu", () -> click(slotNamed(hub2, "hazard")));
            JsonObject hub3 = shoot("SpeedrunLobbyMenu", () -> command("speedrun menu"));
            shoot("SpeedrunRosterMenu", () -> click(slotNamed(hub3, "Who is here")));
            shoot("SpeedrunPreflightMenu", () -> command("speedrun start"));
            shoot("SpeedrunSeedMenu", () -> command("speedrun seed"));
            shoot("SpeedrunSetupMenu", () -> command("speedrun setup"));

            // A run, finished: the history, its summary, stats and the board with it on.
            int beforeRun = mark();
            command("speedrun start");
            JsonObject preflight = Await.value("the pre-flight page", Duration.ofSeconds(10),
                    () -> openedSince(beforeRun, "SpeedrunPreflightMenu").orElse(null));
            click(slotNamed(preflight, "Start the countdown"));
            Game.awaitRacing(bo);
            game.server.console("advancement grant Bo only minecraft:story/mine_stone");
            bo.expectChat("Run finished!");
            JsonObject history = shoot("SpeedrunHistoryMenu", () -> command("speedrun history"));
            shoot("SpeedrunRunSummaryMenu", () -> click(history.getAsJsonArray("buttons").get(0).getAsInt()));
            shoot("SpeedrunStatsMenu", () -> command("speedrun stats Bo"));
            shoot("SpeedrunLeaderboardMenu", () -> command("speedrun top"));
            game.server.console("speedrunreset confirm");
            Game.awaitLobbyItems(bo);

            // Manhunt: the hub and the sides before, the compass list and structure list during, the board after.
            // Shot runs beside Cy, so its team compass has somebody to list; Bo hunts.
            Bot cy = game.player("Cy");
            Game.awaitLobbyItems(cy);
            game.set("game-mode", "manhunt");
            game.set("tracker-team-compass", "true");
            game.set("runner-structure-compass", "true");
            command("manhunt join runner");
            cy.runAndExpect("manhunt join runner", "You are running");
            bo.runAndExpect("manhunt join hunter", "You are hunting");
            shoot("ManhuntHubMenu", () -> command("manhunt"));
            shoot("SidesEditorMenu", () -> command("manhunt sides"));
            int beforeHunt = mark();
            command("speedrun start");
            JsonObject huntPreflight = Await.value("the hunt's pre-flight page", Duration.ofSeconds(10),
                    () -> openedSince(beforeHunt, "SpeedrunPreflightMenu").orElse(null));
            click(slotNamed(huntPreflight, "Start the countdown"));
            Game.awaitRacing(cy);
            Await.ticks(100);
            shoot("ManhuntTrackerMenu", () -> game.server.console("e2e use " + SHOT + " manhunt-team-compass sneak"));
            shoot("StructureChoiceMenu", () -> game.server.console("e2e use " + SHOT + " manhunt-structure-compass"));
            // The hunt ends with Shot alive — a dead real client is a death screen nobody can click away:
            // Shot is moved over to the Hunters, then the last Runner is caught.
            game.server.console("manhunt assign " + SHOT + " hunter");
            Await.ticks(20);
            game.server.console("kill Cy");
            bo.expectChat("Hunters won");
            Await.ticks(60);
            shoot("SpeedrunStandingsMenu", () -> command("manhunt top"));

            List<String> every = Catalog.moduleMenus().stream()
                    .map(name -> name.contains(".") ? name.substring(name.indexOf('.') + 1) : name).toList();
            assertThat(taken.stream().map(file -> file.replace(".png", "")).toList())
                    .as("a screenshot of every page").containsAll(every);
            for (String file : taken) {
                assertThat(Files.size(output().resolve(file))).as(file).isGreaterThan(10_000);
            }
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }
}
