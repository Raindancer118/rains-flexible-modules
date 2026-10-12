package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A moderator quietens one check for themselves; an operator quietens it for everybody; the log keeps it all. */
@Tag("e2e")
class AntiCheatAlertMutesScenarioTest {

    private static final double FLOOR = 101;

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static void hover(Bot bot, double x) {
        for (int t = 0; t < 60; t++) {
            bot.moveTo(x, FLOOR + 2 + t * 0.01, 0.5, false).tickEnd();
            sleep(50);
        }
    }

    @Test
    @DisplayName("/ac alert off fly is for oneself; with global, an operator mutes it for every member of staff")
    void mutes() {
        try (Server server = Server.start("anticheat-mutes", List.of("anticheat-standalone:RainsAntiCheat-.*"),
                List.of("Anti-cheat is up"))) {
            server.console("forceload add -16 -16 16 16");
            server.console("fill -12 100 -12 12 100 12 minecraft:stone");
            server.console("fill -12 101 -12 12 125 12 minecraft:air");
            Bot ada = server.admin("Ada");
            Bot bea = server.admin("Bea");
            Bot one = server.player("One");
            Bot two = server.player("Two");
            server.console("tp One 3.5 " + FLOOR + " 0.5");
            server.console("tp Two -3.5 " + FLOOR + " 0.5");
            Await.ticks(40);

            ada.run("ac alert off fly");
            Await.until("Ada is told", Duration.ofSeconds(10), () -> ada.chatText().stream().anyMatch(line -> line.contains("no longer hear")));
            ada.forgetChat();
            bea.forgetChat();
            hover(one, 3.5);
            Await.until("Bea hears One fly", Duration.ofSeconds(15), () -> bea.chatText().stream()
                    .anyMatch(line -> line.contains("One") && line.contains("Fly")));
            assertThat(ada.chatText()).as("Ada muted it for herself").noneMatch(line -> line.contains("One") && line.contains("Fly"));

            ada.run("ac alert off fly global");
            Await.until("Ada is told it is global", Duration.ofSeconds(10), () -> ada.chatText().stream().anyMatch(line -> line.contains("all staff")));
            bea.forgetChat();
            hover(two, -3.5);
            sleep(7000);
            assertThat(bea.chatText()).as("muted for everybody").noneMatch(line -> line.contains("Two") && line.contains("Fly"));
            assertThat(server.console("anticheat log Two")).as("the evidence goes on").contains("Fly");

            ada.run("ac alert list");
            Await.until("the list says both", Duration.ofSeconds(10), () -> ada.chatText().stream()
                    .anyMatch(line -> line.contains("Muted for you") && line.contains("Fly")));
            assertThat(server.paper.errorsFrom("RainsCore", "RainsAntiCheat")).isEmpty();
        }
    }
}
