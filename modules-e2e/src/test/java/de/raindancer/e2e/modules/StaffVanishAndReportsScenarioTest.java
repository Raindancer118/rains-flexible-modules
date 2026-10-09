package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Staff see a vanished colleague and hear them come and go; a right click dismisses a report. */
@Tag("e2e")
class StaffVanishAndReportsScenarioTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    private static boolean said(Bot bot, String text) {
        return bot.chatText().stream().anyMatch(line -> line.contains(text));
    }

    private static void expect(Bot bot, String what, String text) {
        try {
            Await.until(what, WAIT, () -> said(bot, text));
        } catch (AssertionError failed) {
            throw new AssertionError(failed.getMessage() + " — " + bot.name() + " saw " + bot.chatText(), failed);
        }
    }

    private static int slotNamed(Bot bot, String text) {
        return Await.value("a button named " + text, WAIT, () -> bot.window().flatMap(window -> window.top()
                .entrySet().stream().filter(entry -> entry.getValue().name().contains(text))
                .map(Map.Entry::getKey).findFirst()).orElse(null));
    }

    @Test
    @DisplayName("a vanished admin stays visible to another admin, who is told when they join and leave")
    void staffSeeVanishedStaff() {
        try (Server server = Server.start("staffvanish",
                List.of("moderation-standalone:RainsModeration-.*"), List.of("Moderation is up"))) {
            Bot ada = server.admin("Ada");
            Bot cy = server.admin("Cy");
            Bot bo = server.player("Bo");
            Await.ticks(40);

            ada.run("vanish");
            Await.until("Bo loses Ada", WAIT, () -> !bo.sees(ada.id()));
            Await.ticks(40);
            assertThat(cy.sees(ada.id())).as("the other admin still sees her").isTrue();
            assertThat(cy.unlisted()).as("and she stays in his tablist").doesNotContain(ada.id());

            cy.forgetChat();
            bo.forgetChat();
            ada.leave();
            expect(cy, "Cy is told she left", "[vanished]");
            Await.ticks(20);
            assertThat(bo.chatText()).as("Bo hears nothing").noneMatch(line -> line.contains("Ada"));

            cy.forgetChat();
            ada.rejoin();
            expect(cy, "Cy is told she is back", "[vanished]");
            Await.until("Cy sees her again", WAIT, () -> cy.sees(ada.id()));
            Await.ticks(20);
            assertThat(bo.sees(ada.id())).as("still hidden from Bo").isFalse();
            assertThat(bo.chatText()).noneMatch(line -> line.contains("Ada"));
        }
    }

    @Test
    @DisplayName("right-clicking a report in /reports dismisses it and the reporter is told")
    void rightClickDismissesAReport() {
        try (Server server = Server.start("reportsdismiss",
                List.of("moderation-standalone:RainsModeration-.*"), List.of("Moderation is up"))) {
            Bot ada = server.admin("Ada");
            Bot bo = server.player("Bo");
            Bot cy = server.player("Cy");
            Await.ticks(40);

            bo.run("report Cy flying around my base");
            Await.ticks(40);

            ada.run("reports");
            ada.awaitWindow("Reports");
            int slot = slotNamed(ada, "Cy");
            assertThat(ada.window().orElseThrow().top().get(slot).lore())
                    .anyMatch(line -> line.contains("right click") && line.contains("dismiss"));
            bo.forgetChat();
            ada.forgetChat();
            ada.rightClickSlot(slot);

            expect(ada, "Ada is told it is dismissed", "dismissed");
            expect(bo, "Bo hears what became of it", "Dismissed.");
            Await.until("the queue is empty", WAIT, () -> ada.window()
                    .map(window -> window.top().values().stream().noneMatch(item -> item.name().contains("Cy")))
                    .orElse(false));
        }
    }
}
