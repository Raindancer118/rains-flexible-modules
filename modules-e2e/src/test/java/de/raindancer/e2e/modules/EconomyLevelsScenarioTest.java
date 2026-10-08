package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Experience levels for money and back, on a real server: the levels move, the money moves, by the point. */
@Tag("e2e")
class EconomyLevelsScenarioTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    private static boolean said(Bot bot, String text) {
        return bot.chatText().stream().anyMatch(line -> line.contains(text));
    }

    @Test
    @DisplayName("selling and buying levels")
    void levels() {
        try (Server server = Server.start("economy-levels", List.of("economy-standalone:RainsEconomy-.*"),
                List.of("The economy is up"))) {
            Bot ada = server.admin("Ada");
            server.console("xp add Ada 30 levels");
            Await.ticks(10);
            // Selling: one experience bottle in /sell — click one level, shift click all of it.
            ada.run("sell");
            ada.awaitWindow("Sell");
            ada.click("Sell experience");
            Await.until("one level sold", WAIT, () -> said(ada, "You sold 1 level(s)") && said(ada, "level 29 now"));
            // Buying: the Experience drawer in the shop.
            ada.closeWindow();
            ada.run("shop");
            ada.awaitWindow("Shop");
            ada.click("Experience");
            ada.awaitWindow("Experience");
            ada.click("Buy 1 level(s)");
            Await.until("one bought back", WAIT, () -> said(ada, "You bought 1 level(s)") && said(ada, "level 30 now"));
            ada.closeWindow();
            ada.run("sell");
            ada.awaitWindow("Sell");
            Await.until("the bottle shows the levels", WAIT, () -> ada.window()
                    .flatMap(window -> window.slotNamed("Sell experience")).isPresent());
            ada.shiftClickSlot(ada.window().orElseThrow().slotNamed("Sell experience").orElseThrow());
            Await.until("all of it sold", WAIT, () -> said(ada, "You sold 30 level(s)") && said(ada, "level 0 now"));
            ada.closeWindow();
            assertThat(server.paper.logLines(line -> line.contains("Exception"))).as("nothing threw").isEmpty();
        }
    }
}
