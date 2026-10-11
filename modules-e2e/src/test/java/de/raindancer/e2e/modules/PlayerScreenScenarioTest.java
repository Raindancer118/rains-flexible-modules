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
 * seen, the standing banner), the same slots for the same doors every time, and the console's text version.
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
            Await.until("the screen is filled", WAIT, () -> ada.window().map(w -> slot(w, 1)).orElse("").contains("Online"));
            Bot.Window online = ada.window().orElseThrow();
            dump("Bo (online)", online);
            assertThat(online.items().get(1).material()).isEqualTo("LIME_CONCRETE");
            assertThat(slot(online, 1)).contains("Online now").contains("Ping").contains("Java edition");
            assertThat(slot(online, 2)).contains("Nothing in force");
            assertThat(slot(online, 4)).contains("Bo").contains(bo.id().toString());
            assertThat(slot(online, 9)).contains("Look into");
            assertThat(slot(online, 10)).contains("Record");
            assertThat(slot(online, 23)).contains("Ban");
            assertThat(slot(online, 28)).contains("Inventory");
            assertThat(slot(online, 29)).contains("Go to them");
            ada.closeWindow();

            cy.leave();
            Await.ticks(40);
            ada.forgetChat();
            ada.run("ban Cy griefing");
            Await.until(() -> "Cy is banned (Ada saw " + ada.chatText() + ")", WAIT,
                    () -> ada.chatText().stream().anyMatch(line -> line.contains("Cy is now banned")));
            ada.run("mod Cy");
            Await.ticks(10);
            Await.until("the screen is filled", WAIT, () -> ada.window().map(w -> slot(w, 1)).orElse("").contains("Offline"));
            Bot.Window offline = ada.window().orElseThrow();
            dump("Cy (offline, banned)", offline);
            assertThat(offline.items().get(1).material()).isEqualTo("RED_CONCRETE");
            assertThat(slot(offline, 1)).contains("Last seen");
            assertThat(offline.items().get(2).material()).isEqualTo("RED_BANNER");
            assertThat(slot(offline, 2)).contains("Banned").contains("left").contains("Griefing");
            assertThat(slot(offline, 29)).contains("Go to them");
            assertThat(slot(offline, 10)).as("the same door in the same slot").contains("Record");
            ada.closeWindow();

            server.console("mod Cy");
            server.paper.awaitLog("Banned", WAIT);
            assertThat(server.paper.errorsFrom("RainsCore", "RainsModeration")).isEmpty();
        }
    }
}
