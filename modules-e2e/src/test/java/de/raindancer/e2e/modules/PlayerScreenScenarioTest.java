package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The player screen: status at a glance in the top row (green online, red offline with when they were last
 * seen, the standing banner), three doors to three rooms, the same slots for the same doors every time, and the
 * console's text version.
 */
@Tag("e2e")
class PlayerScreenScenarioTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    private static String slot(Bot.Window window, int slot) {
        Bot.Item item = window.items().get(slot);
        return item == null ? "" : item.name() + " | " + String.join(" / ", item.lore());
    }

    private static void dump(String who, Bot.Window window) {
        Map<Integer, String> top = new TreeMap<>();
        window.items().forEach((slot, item) -> {
            if (slot < 54 && !item.material().contains("GLASS_PANE")) {
                top.put(slot, item.material() + " " + item.name() + " | " + String.join(" / ", item.lore()));
            }
        });
        System.out.println("=== player screen for " + who + " ===");
        top.forEach((slot, text) -> System.out.println(slot + ": " + text));
    }

    @Test
    @DisplayName("an online player reads green, an offline banned one red with the red banner; slots do not move")
    void playerScreen() {
        try (Server server = Server.start("player-screen", List.of("moderation-standalone:RainsModeration-.*"),
                List.of("Moderation is up"))) {
            Bot ada = server.admin("Ada");
            Bot bo = server.player("Bo");
            Bot cy = server.player("Cy");
            cy.say("hello");
            Await.ticks(10);

            ada.run("mod Bo");
            ada.awaitWindow("Moderation");
            Await.until("the screen is filled", WAIT, () -> ada.window().map(w -> slot(w, 3)).orElse("").contains("Online"));
            Bot.Window online = ada.window().orElseThrow();
            dump("Bo (online)", online);
            assertThat(online.items().get(3).material()).isEqualTo("LIME_CONCRETE");
            assertThat(slot(online, 3)).contains("Online now").contains("Ping").contains("Java edition");
            assertThat(slot(online, 5)).contains("Nothing in force");
            assertThat(slot(online, 4)).contains("Bo").contains(bo.id().toString());
            // Three doors, nothing else: the rest is one click further, in a room of its own.
            assertThat(slot(online, 20)).contains("Look into");
            assertThat(slot(online, 22)).contains("Punish").contains("Nothing in force");
            assertThat(slot(online, 24)).contains("Practical").contains("Online now");
            assertThat(online.items().keySet().stream().filter(at -> at >= 9 && at < 45)
                    .filter(at -> !online.items().get(at).material().contains("GLASS_PANE")))
                    .as("no table of buttons on the hub").containsExactlyInAnyOrder(20, 22, 24);

            ada.clickSlot(20);
            ada.awaitWindow("Look into");
            Await.until("the room is filled", WAIT, () -> ada.window().map(w -> slot(w, 11)).orElse("").contains("Record"));
            Bot.Window look = ada.window().orElseThrow();
            dump("Bo — look into", look);
            assertThat(slot(look, 3)).as("the header comes along").contains("Online now");
            assertThat(slot(look, 9)).as("each row says which group it is").contains("Record");
            assertThat(slot(look, 13)).contains("Audit trail");
            assertThat(slot(look, 15)).contains("Attempts");
            assertThat(slot(look, 20)).contains("Reports");
            assertThat(slot(look, 22)).contains("Mining evidence");
            assertThat(slot(look, 24)).contains("Anti-cheat").contains("None on this server");
            assertThat(slot(look, 30)).contains("Chat");
            assertThat(slot(look, 32)).contains("Staff notes");
            ada.closeWindow();

            ada.run("mod Bo");
            ada.awaitWindow("Moderation");
            Await.until("the hub is filled", WAIT, () -> ada.window().map(w -> slot(w, 22)).orElse("").contains("Punish"));
            ada.clickSlot(22);
            ada.awaitWindow("Punish");
            Await.until("the room is filled", WAIT, () -> ada.window().map(w -> slot(w, 11)).orElse("").contains("Warn"));
            Bot.Window punish = ada.window().orElseThrow();
            dump("Bo — punish", punish);
            assertThat(slot(punish, 9)).contains("Escalate");
            assertThat(slot(punish, 13)).contains("Kick");
            assertThat(slot(punish, 15)).contains("Ban");
            assertThat(slot(punish, 21)).contains("Mute");
            assertThat(slot(punish, 23)).contains("Freeze");
            assertThat(slot(punish, 32)).contains("Broke a rule");
            assertThat(punish.items().values()).anyMatch(item -> item.name().contains("Ban for ever"));
            ada.closeWindow();

            ada.run("mod Bo");
            ada.awaitWindow("Moderation");
            Await.until("the hub is filled", WAIT, () -> ada.window().map(w -> slot(w, 24)).orElse("").contains("Practical"));
            ada.clickSlot(24);
            ada.awaitWindow("Practical");
            Await.until("the room is filled", WAIT, () -> ada.window().map(w -> slot(w, 12)).orElse("").contains("Inventory"));
            Bot.Window practical = ada.window().orElseThrow();
            dump("Bo — practical", practical);
            assertThat(slot(practical, 14)).contains("Go to them");
            assertThat(slot(practical, 22)).contains("Fines");
            assertThat(slot(practical, 30)).contains("Tools");
            assertThat(slot(practical, 32)).contains("Staff rank");
            ada.closeWindow();

            // A trial mod: the same page, but what is not theirs is locked, says so, and does nothing when clicked;
            // where Bo stands is not shown to somebody who may not teleport to him.
            Bot dee = server.player("Dee");
            assertThat(server.console("promote Dee trial")).containsIgnoringCase("trial");
            Await.ticks(10);
            dee.run("mod Bo");
            Await.until("Dee's page is filled", WAIT, () -> dee.window().map(w -> slot(w, 3)).orElse("").contains("Online"));
            Bot.Window trial = dee.window().orElseThrow();
            dump("Bo, seen by trial mod Dee", trial);
            assertThat(slot(trial, 3)).doesNotContain("Ping").doesNotContain(" at ");
            dee.clickSlot(24);
            dee.awaitWindow("Practical");
            Await.until("Dee's room is filled", WAIT, () -> dee.window().map(w -> slot(w, 32)).orElse("").contains("Staff rank"));
            Bot.Window trialPractical = dee.window().orElseThrow();
            dump("Bo — practical, seen by trial mod Dee", trialPractical);
            assertThat(slot(trialPractical, 14)).contains("Go to them").contains("For whoever may teleport").doesNotContain("world ");
            assertThat(slot(trialPractical, 32)).contains("Only the server owner");
            dee.clickSlot(32);
            Await.ticks(20);
            assertThat(dee.window().map(w -> slot(w, 4)).orElse("")).as("a locked door opens nothing").contains("Bo");
            dee.clickSlot(14);
            Await.ticks(20);
            assertThat(dee.window()).as("nor does going to him").isPresent();
            dee.closeWindow();

            cy.leave();
            Await.ticks(40);
            ada.forgetChat();
            ada.run("ban Cy griefing");
            Await.until(() -> "Cy is banned (Ada saw " + ada.chatText() + ")", WAIT,
                    () -> ada.chatText().stream().anyMatch(line -> line.contains("Cy is now banned")));
            ada.run("mod Cy");
            Await.ticks(10);
            Await.until("the screen is filled", WAIT, () -> ada.window().map(w -> slot(w, 3)).orElse("").contains("Offline"));
            Bot.Window offline = ada.window().orElseThrow();
            dump("Cy (offline, banned)", offline);
            assertThat(offline.items().get(3).material()).isEqualTo("RED_CONCRETE");
            assertThat(slot(offline, 3)).contains("Last seen");
            assertThat(offline.items().get(5).material()).isEqualTo("RED_BANNER");
            assertThat(slot(offline, 5)).contains("Banned").contains("left").contains("Griefing");
            assertThat(slot(offline, 22)).contains("Punish").contains("Banned");
            assertThat(slot(offline, 24)).contains("Not online");
            assertThat(slot(offline, 20)).as("the same door in the same slot").contains("Look into");
            ada.closeWindow();

            server.console("mod Cy");
            server.paper.awaitLog("Banned", WAIT);
            assertThat(server.paper.errorsFrom("RainsCore", "RainsModeration")).isEmpty();
        }
    }
}
